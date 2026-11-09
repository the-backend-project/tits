package com.github.thxmasj.statemachine;

import java.time.ZonedDateTime;

public record Deadline<T>(
    EntityId entityId,
    EntityModel entityModel,
    int eventNumber,
    EventType<T, ?> eventType,
    T data,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {}
