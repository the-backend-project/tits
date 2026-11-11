package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import com.github.thxmasj.backend.core.database.mssql.ChangeState;
import com.github.thxmasj.backend.core.database.mssql.ConditionalTriggers;
import com.github.thxmasj.backend.core.database.mssql.DeleteDelayedTrigger;
import com.github.thxmasj.backend.core.database.mssql.EntitiesByIndex;
import com.github.thxmasj.backend.core.database.mssql.EventsByEntityId;
import com.github.thxmasj.backend.core.database.mssql.EventsByIdEvent;
import com.github.thxmasj.backend.core.database.mssql.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.database.mssql.Mappers;
import com.github.thxmasj.backend.core.database.mssql.NextDelayedTrigger;
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

  /// The storage in the dialect of the database behind the data source
  ///
  /// @param transitions the transitions of the state machine, which give the entity models and event types
  public static Storage of(
      DataSource dataSource,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    List<EntityModel> entityModels = List.copyOf(transitions.keySet());
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel = Mappers.eventTypesByEntityModel(transitions);
    List<EventType<?, ?>> eventTypes = Mappers.eventTypes(transitions);
    var client = new JDBCClient(dataSource);
    return switch (Dialect.of(dataSource)) {
      case SQL_SERVER -> new Storage(
          new ChangeState(client, schemaName, clock),
          new EventsByEntityId(dataSource, entityModels, schemaName, clock),
          new EventsByIdEvent(dataSource, eventTypesByEntityModel, schemaName, clock),
          new EventsByLastIndexEvent(dataSource, schemaName, clock),
          new EntitiesByIndex(dataSource, eventTypesByEntityModel, schemaName, clock),
          new NextDelayedTrigger(client, eventTypes, clock, entityModels, schemaName),
          new DeleteDelayedTrigger(client, schemaName),
          new ConditionalTriggers(client, eventTypesByEntityModel, clock, schemaName)
      );
      case POSTGRES -> new Storage(
          new com.github.thxmasj.backend.core.database.postgres.ChangeState(client, schemaName, clock),
          new com.github.thxmasj.backend.core.database.postgres.EventsByEntityId(dataSource, entityModels, schemaName, clock),
          new com.github.thxmasj.backend.core.database.postgres.EventsByIdEvent(dataSource, eventTypesByEntityModel, schemaName, clock),
          new com.github.thxmasj.backend.core.database.postgres.EventsByLastIndexEvent(dataSource, schemaName, clock),
          new com.github.thxmasj.backend.core.database.postgres.EntitiesByIndex(dataSource, eventTypesByEntityModel, schemaName, clock),
          new com.github.thxmasj.backend.core.database.postgres.NextDelayedTrigger(client, eventTypes, clock, entityModels, schemaName),
          new DeleteDelayedTrigger(client, schemaName),
          new com.github.thxmasj.backend.core.database.postgres.ConditionalTriggers(client, eventTypesByEntityModel, clock, schemaName)
      );
    };
  }

  /// Creates the schema, or verifies the checksum of the existing one, in the dialect of the database behind the data
  /// source. Completes with 0 if the schema exists.
  public static Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    var client = new JDBCClient(schemaDataSource);
    return Mono.fromCallable(() -> Dialect.of(schemaDataSource))
        .flatMap(dialect -> switch (dialect) {
          case SQL_SERVER -> new com.github.thxmasj.backend.core.database.mssql.CreateSchema(schemaName, role).execute(client);
          case POSTGRES -> new com.github.thxmasj.backend.core.database.postgres.CreateSchema(schemaName, role).execute(client);
        });
  }

}
