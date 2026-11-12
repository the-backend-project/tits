package com.github.thxmasj.backend.core.database;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// Events of the entity having an id, found by the id event of its id entity
public class EventsByIdEvent {

  private final Client client;
  private final Map<UUID, EntityModel> entityModels;
  private final Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel;
  private final Clock clock;
  private final String sql;

  /// @param sql the SQL selecting the events of the entity, with its entity id and entity model id on each row. It
  ///            selects no rows if no entity has the id.
  public EventsByIdEvent(
      Client client,
      String sql,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      Clock clock
  ) {
    this.client = client;
    this.entityModels = eventTypesByEntityModel.keySet().stream().collect(toMap(EntityModel::id, identity()));
    this.eventTypesByEntityModel = eventTypesByEntityModel;
    this.clock = clock;
    this.sql = sql;
  }

  /// Completes empty if no entity has the id
  public Mono<EventLog> execute(EventType<?, ?> type, byte[] id) {
    return client.sql(sql)
        .name("EventsByIdEvent")
        .bind("type", type.id())
        .bind("data", id)
        .map(row -> {
          EntityId entityId = new EntityId.UUID(row.get("EntityId", UUID.class));
          UUID modelId = row.get("EntityModelId", UUID.class);
          EntityModel model = entityModels.get(modelId);
          if (model == null)
            throw new IllegalStateException("Unknown entity model " + modelId + " of entity having id of type " + type.name());
          try {
            return new ModelEvent(model, entityId, Mappers.eventMapper(eventTypesByEntityModel.get(model), clock).apply(entityId, row));
          } catch (MappingFailure e) {
            throw new MappingFailure("EventsByIdEvent: Failed to map row to event for entity model " + model.name(), e);
          }
        })
        .all()
        .collectList()
        .filter(events -> !events.isEmpty())
        .map(events -> new EventLog(
            events.getFirst().model(),
            events.getFirst().entityId(),
            Collections.unmodifiableList(events.stream().<Event<?>>map(ModelEvent::event).toList())
        ));
  }

  private record ModelEvent(EntityModel model, EntityId entityId, Event<?> event) {}

}
