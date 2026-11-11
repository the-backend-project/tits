package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;

public record DelayedTrigger<T>(
    EntityId entityId,
    EntityModel entityModel,
    int eventNumber,
    EventType<T, ?> eventType,
    T data,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {}
