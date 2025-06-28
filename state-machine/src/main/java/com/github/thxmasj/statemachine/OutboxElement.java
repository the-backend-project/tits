package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;

public record OutboxElement(
    byte[] queueElementId,
    UUID requestId,
    EntityId entityId,
    EntityModel entityModel,
    int eventNumber,
    UUID creatorId,
    OutboxQueue queue,
    boolean guaranteed,
    ZonedDateTime enqueuedAt,
    HttpRequestMessage data,
    String correlationId,
    int attempt,
    ZonedDateTime nextAttemptAt,
    ZonedDateTime processedAt
) {

  public Duration backoff() {
    return processedAt == null || nextAttemptAt == null ? null : Duration.between(processedAt, nextAttemptAt);
  }
}
