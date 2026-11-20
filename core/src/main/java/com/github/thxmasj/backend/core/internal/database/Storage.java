package com.github.thxmasj.backend.core.internal.database;

/// The queries and updates of a state machine's storage, by the [StorageProvider] of its database. The operations
/// state what they store and find in terms of the state machine, so that a database that is not reached through SQL
/// can provide them too. The implementations for the databases reached through SQL are in the package `sql`.
public record Storage(
    ChangeState changeState,
    EventsByEntityId eventsByEntityId,
    EventsByIdEvent eventsByIdEvent,
    EventsByLastIndexEvent eventsByLastIndexEvent,
    EntitiesByIndex entitiesByIndex,
    NextDelayedTrigger nextDelayedTrigger,
    DeleteDelayedTrigger deleteDelayedTrigger,
    ConditionalTriggers conditionalTriggers
) {}
