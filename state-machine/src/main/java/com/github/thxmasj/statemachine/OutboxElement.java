package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.function.UnaryOperator;

public record OutboxElement(
    byte[] id,
    long outboxElementId,
    EntityId entityId,
    EntityModel entityModel,
    int eventNumber,
    Subscriber subscriber,
    boolean guaranteed,
    ZonedDateTime enqueuedAt,
    String data,
    String correlationId,
    int attempt,
    ZonedDateTime nextAttemptAt,
    ZonedDateTime processedAt
) {

  public String data(UnaryOperator<String> reattemptTransformation) {
    return attempt > 1 ? reattemptTransformation.apply(data) : data;
  }

  public Duration backoff() {
    return processedAt == null || nextAttemptAt == null ? null : Duration.between(processedAt, nextAttemptAt);
  }
}
