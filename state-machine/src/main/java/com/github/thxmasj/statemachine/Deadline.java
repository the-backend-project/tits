package com.github.thxmasj.statemachine;

import java.time.ZonedDateTime;

public record Deadline(
    EntityId entityId,
    EntityModel entityModel,
    int eventNumber,
    ZonedDateTime nextAttemptAt,
    String correlationId
) {}
