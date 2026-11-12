package com.github.thxmasj.backend.core.database;

import static java.util.Objects.requireNonNull;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public class EventsByEntityId {

  private final Client client;
  private final Map<EntityModel, String> sqls;
  private final Clock clock;

  /// @param sql the SQL selecting the events of an entity from an event table, given the table name
  public EventsByEntityId(
      Client client,
      Function<String, String> sql,
      List<EntityModel> entityModels,
      Clock clock
  ) {
    this.client = client;
    this.clock = clock;
    this.sqls = new HashMap<>();
    for (var entityModel : entityModels) {
      String table = switch (entityModel) {
        case IdEntityModel _ -> "IdEvent";
        case IndexEntityModel _ -> "IndexEvent";
        default -> "Event";
      };
      this.sqls.put(entityModel, sql.apply(table));
    }
  }

  public Mono<EventLog> execute(EntityModel entityModel, List<EventType<?, ?>> eventTypes, EntityId entityId) {
    String sqlToPrepare = requireNonNull(sqls.get(entityModel), "Unknown model " + entityModel.name());
    var eventMapper = Mappers.eventMapper(eventTypes, clock);
    return client.sql(sqlToPrepare)
        .name("EventsByEntityId")
        .bind("entityId", entityId.value())
        .<Event<?>>map(row -> {
          try {
            return eventMapper.apply(entityId, row);
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByEntityId: Failed to map row to event for entity model " + entityModel.name(), e);
          }
        })
        .all()
        .collectList()
        .map(events -> {
          if (events.isEmpty())
            throw new UnknownEntity(entityModel, entityId);
          return new EventLog(entityModel, entityId, Collections.unmodifiableList(events));
        });
  }

}
