package com.github.thxmasj.statemachine;

import java.time.ZonedDateTime;

public record Deadline(
    EntityId entityId,
    int eventNumber,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {}
