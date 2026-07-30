package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.time.ZonedDateTime;

public record OutboxElement(
    byte[] queueElementId,
    EventLog requestLog,
    String correlationId,
    int attempt,
    ZonedDateTime nextAttemptAt,
    ZonedDateTime processedAt
) {

  public Duration backoff() {
    return processedAt == null || nextAttemptAt == null ? null : Duration.between(processedAt, nextAttemptAt);
  }
}
