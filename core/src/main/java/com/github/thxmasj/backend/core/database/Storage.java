package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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

  /// The storage of the database behind the data source (see [Database#of(DataSource)])
  ///
  /// @param transitions the transitions of the state machine, which give the entity models and event types
  public static Storage of(
      DataSource dataSource,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    Database database = Database.of(dataSource);
    return database.storage(database.client(dataSource), transitions, schemaName, clock);
  }

  /// Creates the schema, or verifies the checksum of the existing one, in the database behind the data source.
  /// Completes with 0 if the schema exists.
  public static Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return Mono.fromCallable(() -> Database.of(schemaDataSource))
        .flatMap(database -> database.createSchema(schemaDataSource, schemaName, role));
  }

}
