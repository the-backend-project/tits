package com.github.thxmasj.backend.core;

import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

class Correlation {

  private static final String CORRELATION_ID = "correlationId";

  public static Mono<String> correlationId() {
    return Mono.deferContextual(Mono::just)
        .map(ctx -> ctx.<String>get(CORRELATION_ID))
        .contextWrite(ctx -> ctx.hasKey(CORRELATION_ID) ? ctx : ctx.put(CORRELATION_ID, "N/A-xxx"));// + UUID.randomUUID().toString()));
  }

  public static ContextView contextOf(@NonNull String correlationId) {
    return Context.of(CORRELATION_ID, correlationId);
  }

  public static String correlationId(ContextView context) {
    return context.get(CORRELATION_ID);
  }

}
