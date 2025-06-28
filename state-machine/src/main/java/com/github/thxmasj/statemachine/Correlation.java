package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.annotation.NonNull;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import java.util.UUID;

public class Correlation {

  private static final String CORRELATION_ID = "correlationId";
  private static final String RESPONSE_SINK = "responseSink";
  private static final String REQUEST_ID = "requestId";

  public static Mono<String> correlationId() {
    return Mono.deferContextual(Mono::just)
        .map(ctx -> ctx.<String>get(CORRELATION_ID))
        .contextWrite(ctx -> ctx.hasKey(CORRELATION_ID) ? ctx : ctx.put(CORRELATION_ID, UUID.randomUUID().toString()));
  }

  public static ContextView contextOf(@NonNull String correlationId, Sinks.One<HttpResponseMessage> responseSink, UUID requestId) {
    return Context.of(CORRELATION_ID, correlationId, RESPONSE_SINK, responseSink, REQUEST_ID, requestId);
  }

  public static ContextView contextOf(@NonNull String correlationId) {
    return Context.of(CORRELATION_ID, correlationId);
  }

  public static String correlationId(ContextView context) {
    return context.get(CORRELATION_ID);
  }

  public static UUID requestId(ContextView context) {
    return context.get(REQUEST_ID);
  }

  public static boolean hasRequestId(ContextView context) {
    return context.hasKey(REQUEST_ID);
  }

  public static Sinks.One<HttpResponseMessage> responseSink(ContextView context) {
    return context.get(RESPONSE_SINK);
  }

  public static boolean hasResponseSink(ContextView context) {
    return context.hasKey(RESPONSE_SINK);
  }

}
