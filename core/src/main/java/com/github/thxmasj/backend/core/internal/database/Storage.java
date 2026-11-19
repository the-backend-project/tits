package com.github.thxmasj.backend.core.internal.database;

import com.github.thxmasj.backend.core.database.Database;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// The queries and updates of a state machine's database schema, in the dialect of its database
public record Storage(
    ChangeState changeState,
    EventsByEntityId eventsByEntityId,
    EventsByIdEvent eventsByIdEvent,
    EventsByLastIndexEvent eventsByLastIndexEvent,
    EntitiesByIndex entitiesByIndex,
    NextDelayedTrigger nextDelayedTrigger,
    DeleteDelayedTrigger deleteDelayedTrigger,
    ConditionalTriggers conditionalTriggers
) {

  /// Creates the schema, or verifies the checksum of the existing one, in the database behind the data source.
  /// Completes with 0 if the schema exists.
  public static Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return Mono.fromCallable(() -> Database.of(schemaDataSource))
        .flatMap(database -> database.createSchema(schemaDataSource, schemaName, role));
  }

}
