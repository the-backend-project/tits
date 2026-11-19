package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
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
