package com.github.thxmasj.backend.backoffice;

import com.github.thxmasj.backend.http.HttpClient;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpResponse;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import reactor.core.publisher.Mono;

/// The HTTP client the token requests of the sign-ins are sent with, by the HTTP client of the JDK, as the OpenID
/// Connect provider is called otherwise. The client of reactor-netty can't be built into a native image with its TLS
/// context, which holds a `SecureRandom` created when the image is built.
///
/// A connection which fails is told the outbox as a [ConnectException], as the outbox treats it as a request never
/// sent.
final class JdkHttpClient implements HttpClient {

  private static final Duration timeout = Duration.ofSeconds(10);
  /// The headers the HTTP client of the JDK sets itself
  private static final Set<String> restrictedHeaders = Set.of("connection", "content-length", "expect", "host", "upgrade");

  private final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build();

  @Override
  public Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> message) {
    var request = java.net.http.HttpRequest.newBuilder(message.uri()).timeout(timeout);
    message.headers().forEach((name, value) -> {
      if (!restrictedHeaders.contains(name.toLowerCase()))
        request.header(name, value);
    });
    byte[] body = message.body();
    request.method(message.method().name(), body != null ? BodyPublishers.ofByteArray(body) : BodyPublishers.noBody());
    return Mono.fromFuture(() -> client.sendAsync(request.build(), BodyHandlers.ofByteArray()))
        .map(response -> {
          var headers = new LinkedHashMap<String, String>();
          response.headers().map().forEach((name, values) -> headers.put(name, String.join(", ", values)));
          byte[] responseBody = response.body();
          return new HttpResponse<>(response.statusCode(), "", headers, responseBody != null && responseBody.length > 0 ? responseBody : null);
        })
        .onErrorMap(CompletionException.class, e -> e.getCause() != null ? e.getCause() : e)
        .onErrorMap(HttpConnectTimeoutException.class, e -> (ConnectException) new ConnectException(e.getMessage()).initCause(e));
  }

}
