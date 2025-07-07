package com.github.thxmasj.statemachine.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class AccessTokenSupplier implements Supplier<String> {

  private final AtomicReference<AccessToken> cachedToken = new AtomicReference<>();
  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
  private final Clock clock;
  private final CountDownLatch readyLatch = new CountDownLatch(1);

  private boolean shutDown;

  private final HttpClient httpClient;
  private final URI url;
  private final Map<String, String> headers;
  private final String body;

  private AccessTokenSupplier(
      URI url,
      Map<String, String> headers,
      String body,
      Clock clock,
      HttpClient httpClient
  ) {
    this.url = url;
    this.headers = headers;
    this.body = body;
    this.clock = clock;
    this.httpClient = httpClient;
    scheduleFetch(Duration.ZERO);
  }


  private void scheduleFetch(Duration duration) {
    if (shutDown)
      return;
    scheduler.schedule(this::fetch, duration.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void fetch() {
    httpClient.exchange(new HttpRequestMessage(Method.POST, url, headers, body))
        .doOnNext(this::onNext)
        .doOnError(this::onError)
        .block();
  }

  private void onNext(HttpResponseMessage item) {
    if (item.statusCode() < 200 || item.statusCode() > 299) {
      onError(new AccessFailed(String.format(
              "Could not get access token from %s. HTTP status: %s. HTTP payload: %s",
              url.toString(),
              item.statusCode(),
              item.body()
          ))
      );
      return;
    }
    AccessToken token;
    try {
      token = AccessToken.parse(item.body(), clock);
    } catch (Exception e) {
      onError(e);
      return;
    }
    cachedToken.set(token);
    readyLatch.countDown();
    scheduleFetch(Duration.between(clock.instant(), token.expiry().minusSeconds(10)));
  }

  @Override
  public String get() {
    long t0 = System.currentTimeMillis();
    try {
      if (!readyLatch.await(10, TimeUnit.SECONDS))
        throw new AccessFailed("No access token available");
    } catch (InterruptedException e) {
      throw new AccessFailed(
          "Interrupted while waiting for access token after %d ms (from: %s)"
          .formatted(
              System.currentTimeMillis() - t0,
              Stream.of(e.getStackTrace()).map(StackTraceElement::toString).collect(joining("\n"))
          ),
          e
      );
    }
    return cachedToken.get().token();
  }

  private void onError(Throwable throwable) {
    scheduleFetch(Duration.ofSeconds(5));
  }

  public synchronized void shutDown() {
    shutDown = true;
    scheduler.shutdown();
  }

  public static class Builder {

    private URI url;
    private HttpClient httpClient;
    private Clock clock = Clock.systemDefaultZone();
    private final Map<String, String> headers = new HashMap<>(Map.of(
        "Content-Type",
        "application/x-www-form-urlencoded"
    ));
    private String grantType;
    private final List<String> scopes = new ArrayList<>();

    public Builder(HttpClient httpClient, URI uri) {
      this.httpClient = httpClient;
      this.url = uri;
    }

    public Builder clientCredentials(String id, String secret) {
      headers.put(
          "Authorization",
          "Basic " + Base64.getEncoder().encodeToString((URLEncoder.encode(id, UTF_8) + ":" + URLEncoder.encode(secret, UTF_8)).getBytes(UTF_8))
      );
      return grantType("client_credentials");
    }

    private Builder grantType(String grantType) {
      this.grantType = grantType;
      return this;
    }

    public Builder scopes(List<String> scopes) {
      this.scopes.addAll(scopes);
      return this;
    }

    public Builder clock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public AccessTokenSupplier build() {
      requireNonNull(grantType, "grant type");
      return new AccessTokenSupplier(url, headers, createBody(), clock, httpClient);
    }

    private String createBody() {
      var body = new StringBuilder("grant_type=").append(grantType);
      if (!scopes.isEmpty()) {
        body.append("&").append("scope=").append(URLEncoder.encode(String.join(" ", scopes), UTF_8));
      }
      return body.toString();
    }
  }

  public static class AccessFailed extends RuntimeException {
    public AccessFailed(String message, Throwable cause) {
      super(message, cause);
    }
    public AccessFailed(String message) {
      super(message);
    }
  }

}
