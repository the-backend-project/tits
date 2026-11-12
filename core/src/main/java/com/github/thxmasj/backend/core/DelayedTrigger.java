package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;
import java.util.UUID;

public record DelayedTrigger<T>(
    UUID entityId,
    EntityModel entityModel,
    int eventNumber,
    EventType<T, ?> eventType,
    T data,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {}
