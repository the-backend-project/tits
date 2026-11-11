package com.github.thxmasj.backend.core;

import java.time.ZonedDateTime;
import java.util.UUID;

public record DelayedEvent<T>(
    EntityModel entityModel,
    UUID entityId,
    EventType<T, ?> type,
    int eventNumber,
    T data,
    ZonedDateTime after
) {
}
