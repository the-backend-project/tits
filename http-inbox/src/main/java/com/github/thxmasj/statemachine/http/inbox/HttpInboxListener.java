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
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/// Receives HTTP requests with reactor-netty and feeds them to the HTTP inbox of a state machine. The response is taken
/// from the response event produced by the inbox' request routing.
public class HttpInboxListener {

  private final StateMachine stateMachine;
  private final Duration responseTimeout;

  public HttpInboxListener(StateMachine stateMachine) {
    this(stateMachine, Duration.ofSeconds(30));
  }

  public HttpInboxListener(StateMachine stateMachine, Duration responseTimeout) {
    this.stateMachine = stateMachine;
    this.responseTimeout = responseTimeout;
  }

  /// Starts listening on the given port. Use port 0 to bind to an ephemeral port.
  public DisposableServer listen(int port) {
    return listen(HttpServer.create().port(port));
  }

  /// Starts listening with the given server, which can be preconfigured with e.g. host, port, TLS and protocols.
  public DisposableServer listen(HttpServer server) {
    return server.handle(this::handle).bindNow();
  }

  private Mono<Void> handle(HttpServerRequest request, HttpServerResponse response) {
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
        .flatMap(this::exchange)
        .timeout(responseTimeout)
        .flatMap(message -> send(response, message))
        .onErrorResume(TimeoutException.class, _ -> response.status(HttpResponseStatus.GATEWAY_TIMEOUT).send())
        .onErrorResume(_ -> response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).send());
  }

  private Mono<HttpResponseMessage> exchange(HttpRequestMessage requestMessage) {
    return stateMachine.onEvent(UUID.randomUUID().toString(), TRIGGER, requestMessage)
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        .map(event -> HttpMessageParser.parseResponse(event.data()))
        .switchIfEmpty(Mono.error(() -> new IllegalStateException("No response event for " + requestMessage.requestLine())));
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
