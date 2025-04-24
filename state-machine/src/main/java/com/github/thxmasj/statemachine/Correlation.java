package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;
import reactor.util.context.Context;
import reactor.util.context.ContextView;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;

public class Correlation {

  private static final String CORRELATION_ID = "correlationId";

  public static final Supplier<ContextView> newCorrelationIdContext =
      () -> Context.of(CORRELATION_ID, newCorrelationId());

  public static Mono<String> correlationId() {
    return Mono.deferContextual(Mono::just)
        .map(ctx -> ctx.<String>get(CORRELATION_ID))
        .contextWrite(ctx -> ctx.hasKey(CORRELATION_ID) ? ctx : ctx.put(CORRELATION_ID, newCorrelationId()));
  }

  public static ContextView correlationIdContextOfNullable(String correlationId) {
    return ofNullable(correlationId)
        .filter(not(String::isEmpty))
        .map(Correlation::correlationIdContext)
        .orElseGet(Correlation.newCorrelationIdContext);
  }

  public static ContextView correlationIdContext(@NonNull String correlationId) {
    return Context.of(CORRELATION_ID, correlationId);
  }

  private static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }

}
