package com.github.thxmasj.statemachine.http.inbox;

import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.TRIGGER;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.StateMachine;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/// Receives HTTP requests with reactor-netty and feeds them to the HTTP inbox of a state machine. The response is taken
/// from the response event produced by the inbox' request routing.
///
/// Filters can be added to e.g. authenticate, log, rewrite or short-circuit requests before they reach the inbox.
public class HttpInboxListener {

  /// Intercepts a request on its way to the inbox. A filter can pass the request, possibly modified, on to `next`, or
  /// respond by itself without calling `next`.
  @FunctionalInterface
  public interface Filter {

    Mono<HttpResponseMessage> filter(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next);

  }

  private final StateMachine stateMachine;
  private final Duration responseTimeout;
  private final String correlationIdHeader;
  private final List<Filter> filters;

  public HttpInboxListener(StateMachine stateMachine) {
    this(stateMachine, Duration.ofSeconds(30));
  }

  public HttpInboxListener(StateMachine stateMachine, Duration responseTimeout) {
    this(stateMachine, responseTimeout, "X-Correlation-Id", List.of());
  }

  private HttpInboxListener(
      StateMachine stateMachine,
      Duration responseTimeout,
      String correlationIdHeader,
      List<Filter> filters
  ) {
    this.stateMachine = stateMachine;
    this.responseTimeout = responseTimeout;
    this.correlationIdHeader = correlationIdHeader;
    this.filters = List.copyOf(filters);
  }

  /// Returns a listener which also applies the given filter. Filters are applied in the order they are added, so the
  /// first filter added sees the request first.
  public HttpInboxListener filter(Filter filter) {
    List<Filter> filters = new ArrayList<>(this.filters);
    filters.add(filter);
    return new HttpInboxListener(stateMachine, responseTimeout, correlationIdHeader, filters);
  }

  /// Returns a listener which takes the correlation id of the triggered events from the given request header, e.g.
  /// `TID`. The default header is `X-Correlation-Id`. Requests without the header get a generated correlation id
  /// prefixed with `SYS-`.
  public HttpInboxListener correlationIdHeader(String correlationIdHeader) {
    return new HttpInboxListener(stateMachine, responseTimeout, correlationIdHeader, filters);
  }

  /// Starts listening on the given port. Use port 0 to bind to an ephemeral port.
  public DisposableServer listen(int port) {
    return listen(HttpServer.create().port(port));
  }

  /// Starts listening with the given server, which can be preconfigured with e.g. host, port, TLS and protocols.
  public DisposableServer listen(HttpServer server) {
    Function<HttpRequestMessage, Mono<HttpResponseMessage>> chain = this::exchange;
    for (Filter filter : filters.reversed()) {
      Function<HttpRequestMessage, Mono<HttpResponseMessage>> next = chain;
      chain = request -> filter.filter(request, next);
    }
    Function<HttpRequestMessage, Mono<HttpResponseMessage>> filteredExchange = chain;
    return server.handle((request, response) -> handle(request, response, filteredExchange)).bindNow();
  }

  private Mono<Void> handle(
      HttpServerRequest request,
      HttpServerResponse response,
      Function<HttpRequestMessage, Mono<HttpResponseMessage>> exchange
  ) {
    Method method;
    try {
      method = Method.valueOf(request.method().name());
    } catch (IllegalArgumentException e) {
      return response.status(HttpResponseStatus.METHOD_NOT_ALLOWED).send();
    }
    return request.receive().aggregate().asByteArray()
        .filter(body -> body.length > 0)
        .map(body -> requestMessage(request, method, body))
        .switchIfEmpty(Mono.fromSupplier(() -> requestMessage(request, method, null)))
        .flatMap(exchange)
        .timeout(responseTimeout)
        .flatMap(message -> send(response, message))
        .onErrorResume(TimeoutException.class, _ -> response.status(HttpResponseStatus.GATEWAY_TIMEOUT).send())
        .onErrorResume(_ -> response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send());
  }

  private Mono<HttpResponseMessage> exchange(HttpRequestMessage requestMessage) {
    return stateMachine.onEvent(correlationId(requestMessage), TRIGGER, requestMessage)
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        .map(event -> HttpMessageParser.parseResponse(event.data()))
        .switchIfEmpty(Mono.error(() -> new IllegalStateException("No response event for " + requestMessage.requestLine())));
  }

  private String correlationId(HttpRequestMessage requestMessage) {
    String correlationId = requestMessage.headerValue(correlationIdHeader);
    if (correlationId == null || correlationId.isBlank())
      return "SYS-" + ThreadLocalRandom.current().nextLong(1000000000000000000L, Long.MAX_VALUE);
    return correlationId;
  }

  private static HttpRequestMessage requestMessage(HttpServerRequest request, Method method, byte[] body) {
    Map<String, String> headers = request.requestHeaders().entries().stream()
        .collect(toMap(Entry::getKey, Entry::getValue, (a, b) -> a + ", " + b));
    return new HttpRequestMessage(method, URI.create(request.uri()), headers, body);
  }

  private static Mono<Void> send(HttpServerResponse response, HttpResponseMessage message) {
    response.status(HttpResponseStatus.valueOf(message.statusCode(), message.reasonPhrase()));
    message.headers().forEach(response::header);
    return message.body() == null
        ? response.send()
        : response.send(Mono.just(Unpooled.wrappedBuffer(message.body()))).then();
  }

}
