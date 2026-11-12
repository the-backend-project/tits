package com.github.thxmasj.backend.core.database;

import static java.lang.String.format;

import com.github.thxmasj.backend.core.ConditionalTrigger;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventType;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;

public class ConditionalTriggers {

  private final DatabaseClient databaseClient;
  private final Clock clock;
  private final Map<UUID, Map<UUID, EventType<?, ?>>> eventTypesByEntityModelId;
  private final Map<UUID, EntityModel> entityModels;
  private final String sql;

  /// @param sql the SQL selecting the oldest conditional triggers whose condition is met, at most `:limit` of them
  public ConditionalTriggers(
      DatabaseClient databaseClient,
      String sql,
      Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
      Clock clock
  ) {
    this.databaseClient = databaseClient;
    this.eventTypesByEntityModelId = eventTypesByEntityModel.entrySet().stream().collect(Collectors.toMap(
        e -> e.getKey().id(),
        e -> e.getValue().stream().collect(Collectors.toMap(EventType::id, Function.identity()))
    ));
    this.clock = clock;
    this.entityModels = eventTypesByEntityModel.keySet().stream()
        .collect(Collectors.toMap(EntityModel::id, Function.identity()));
    this.sql = sql;
  }

  /**
   * The oldest waiting conditional triggers whose condition is met, at most the given limit.
   */
  public Flux<ConditionalTrigger<?>> execute(int limit) {
    return databaseClient.sql(sql)
        .name("ConditionalTriggers")
        .bind("limit", limit)
        .<ConditionalTrigger<?>>map(this::map)
        .all();
  }

  private ConditionalTrigger<?> map(Row row) {
    UUID entityModelId = row.get("EntityModelId", UUID.class);
    EntityModel entityModel = entityModels.get(entityModelId);
    if (entityModel == null)
      throw new MappingFailure(format("No entity model for id %s", entityModelId));
    return create(
        row.get("Id", UUID.class),
        new EventReference(row.get("TriggerEntityId", UUID.class), row.get("TriggerEventNumber", Integer.class)),
        entityModel,
        row.get("EntityId", UUID.class),
        eventType(entityModel, row.get("Type", UUID.class)),
        row.get("Data", byte[].class),
        ZonedDateTime.of(row.get("Timestamp", LocalDateTime.class), clock.getZone()),
        anyEventType(row.get("ReferenceType", UUID.class)),
        Arrays.stream(row.get("LastTypes", String.class).split(","))
            .<EventType<?, ?>>map(id -> anyEventType(UUID.fromString(id)))
            .toList(),
        DataType.uuid().unmarshal(row.get("IndexKey", byte[].class)),
        row.get("CorrelationId", String.class)
    );
  }

  private EventType<?, ?> eventType(EntityModel entityModel, UUID eventTypeId) {
    EventType<?, ?> eventType = eventTypesByEntityModelId.get(entityModel.id()).get(eventTypeId);
    if (eventType == null)
      throw new MappingFailure(format("No event type for type id %s in entity model %s", eventTypeId, entityModel.name()));
    return eventType;
  }

  /**
   * Reference and last types belong to the referenced entities, whose entity model is not known here. They are only
   * used for their id and name, so any entity model's event type with a matching id will do.
   */
  private EventType<?, ?> anyEventType(UUID eventTypeId) {
    return eventTypesByEntityModelId.values().stream()
        .map(eventTypes -> eventTypes.get(eventTypeId))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new MappingFailure(format("No event type for type id %s", eventTypeId)));
  }

  private <T> ConditionalTrigger<T> create(
      UUID id,
      EventReference triggerEvent,
      EntityModel entityModel,
      UUID entityId,
      EventType<T, ?> type,
      byte[] data,
      ZonedDateTime timestamp,
      EventType<?, ?> referenceType,
      List<EventType<?, ?>> lastTypes,
      UUID indexKey,
      String correlationId
  ) {
    return new ConditionalTrigger<>(
        id,
        triggerEvent,
        entityModel,
        entityId,
        type,
        data != null ? type.inputDataType().unmarshal(data) : null,
        timestamp,
        referenceType,
        lastTypes,
        indexKey,
        correlationId
    );
  }

}
