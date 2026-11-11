package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public record ConditionalTrigger<T>(
    UUID id,
    EventReference triggerEvent,
    EntityModel entityModel,
    UUID entityId,
    EventType<T, ?> type,
    T data,
    ZonedDateTime timestamp,
    EventType<?, ?> referenceType,
    List<EventType<?, ?>> lastTypes,
    UUID indexKey,
    String correlationId
) {
}
