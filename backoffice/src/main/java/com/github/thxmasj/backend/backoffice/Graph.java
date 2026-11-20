package com.github.thxmasj.backend.backoffice;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.BuiltinEventTypes;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.internal.EntityModels;
import com.github.thxmasj.backend.core.internal.EventTypes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// Reads the entities of a state machine for the backoffice: their event logs, ids, index keys and pending triggers,
/// and the entities related to them. Like the test API, it knows the entity models as given to the state machine,
/// and the event types through their transitions, and only reads.
///
/// An entity refers to other entities by their entity ids in the data of its events, e.g. an inbox request to the
/// entity its request was processed on. It is referred to by the entities having an index event with its entity id as
/// key, e.g. the inbox and outbox requests of the entity.
final class Graph {

  /// The most entities whose entity ids are looked up at once
  private static final int maxLookup = 200;

  private final DatabaseClient client;
  private final String schema;
  private final Map<UUID, EntityModel> entityModels;
  private final Function<UUID, EventType<?, ?>> eventTypeMapper;
  /// The index event types whose keys are entity ids, by which entities refer to other entities
  private final List<EventType<?, ?>> referenceTypes;
  /// The id event types whose ids are strings or UUIDs, which entities can be searched for by
  private final List<EventType<?, ?>> searchableIdTypes;
  /// The index event types whose keys are strings, which entities can be searched for by
  private final List<EventType<?, ?>> searchableIndexTypes;

  Graph(DatabaseClient client, String schema, List<? extends EntityModel> entityModels) {
    List<EntityModel> models = EntityModels.resolve(entityModels);
    this.client = client;
    this.schema = schema;
    this.entityModels = models.stream().collect(toMap(EntityModel::id, identity()));
    Map<UUID, EventType<?, ?>> eventTypesById = EventTypes.eventTypes(models).stream().collect(toMap(EventType::id, identity()));
    this.eventTypeMapper = eventTypesById::get;
    var eventTypesByModel = EventTypes.eventTypesByEntityModel(models);
    var referenceTypes = new LinkedHashSet<EventType<?, ?>>();
    var idTypes = new LinkedHashSet<EventType<?, ?>>();
    var indexTypes = new LinkedHashSet<EventType<?, ?>>();
    eventTypesByModel.entrySet().stream()
        .sorted(Comparator.comparing(e -> e.getKey().name()))
        .forEach(e -> {
          for (EventType<?, ?> type : e.getValue()) {
            if (BuiltinEventTypes.ALL.contains(type))
              continue;
            DataType<?> dataType = type.outputDataType();
            if (e.getKey() instanceof IndexEntityModel) {
              if (dataType instanceof DataType.UUIDDataType)
                referenceTypes.add(type);
              else if (dataType instanceof DataType.StringDataType)
                indexTypes.add(type);
            } else if (e.getKey() instanceof IdEntityModel
                && (dataType instanceof DataType.UUIDDataType || dataType instanceof DataType.StringDataType)) {
              idTypes.add(type);
            }
          }
        });
    this.referenceTypes = List.copyOf(referenceTypes);
    this.searchableIdTypes = List.copyOf(idTypes);
    this.searchableIndexTypes = List.copyOf(indexTypes);
  }

  /// An entity, of an entity model the backoffice may not know if the entity was created by another version of the
  /// application
  ///
  /// @param model the entity model, or null if it is unknown
  record Entity(UUID id, UUID modelId, EntityModel model) {

    String modelName() {
      return model != null ? model.name() : modelId.toString();
    }

  }

  /// An event of an entity, or an id or index event
  ///
  /// @param type the event type, or null if it is unknown
  record StoredEvent(int number, UUID typeId, EventType<?, ?> type, byte[] data, LocalDateTime timestamp) {

    String typeName() {
      return type != null ? type.name() : typeId.toString();
    }

  }

  /// The event an entity is waiting for, triggered at the deadline if the entity has no other event before that
  record Delayed(int eventNumber, StoredEvent event, LocalDateTime deadline, int attempt) {}

  /// An event an entity is waiting for, triggered when the entities indexed by the key with the reference type all have
  /// a last event of one of the last types
  ///
  /// @param trigger the entity whose event created the trigger
  record Conditional(StoredEvent event, List<String> lastTypes, String referenceType, byte[] indexKey, UUID trigger, int triggerEventNumber) {}

  /// An entity referring to another by an index event with its entity id as key
  record Referrer(Entity entity, String indexType) {}

  /// An entity with everything recorded about it
  record View(
      Entity entity,
      List<StoredEvent> events,
      List<StoredEvent> ids,
      List<StoredEvent> indexKeys,
      Optional<Delayed> delayed,
      List<Conditional> conditionals,
      List<Referrer> referrers
  ) {}

  /// An entity found by a search
  ///
  /// @param matchedBy what it was found by, e.g. the name of an id event type
  record Match(Entity entity, String matchedBy) {}

  /// An entity listed with when it was created
  record Listed(Entity entity, LocalDateTime created, String firstEvent) {}

  /// The entity models of entities, which have their own event logs, in the order of their names
  List<EntityModel> models() {
    return entityModels.values().stream()
        .filter(m -> !(m instanceof IdEntityModel) && !(m instanceof IndexEntityModel))
        .sorted(Comparator.comparing(EntityModel::name))
        .toList();
  }

  Optional<EntityModel> model(UUID id) {
    return Optional.ofNullable(entityModels.get(id));
  }

  /// The entity with the entity id. Completes empty if there is none, also for an id or index entity, which has no
  /// entity model stored.
  Mono<Entity> entity(UUID entityId) {
    return entities(List.of(entityId)).flatMap(entities -> Mono.justOrEmpty(entities.get(entityId)));
  }

  /// The entities with the entity ids, by their entity ids. Ids of no entity are left out, as are ids beyond the first
  /// [#maxLookup].
  Mono<Map<UUID, Entity>> entities(Collection<UUID> entityIds) {
    List<UUID> ids = entityIds.stream().distinct().limit(maxLookup).toList();
    if (ids.isEmpty())
      return Mono.just(Map.of());
    var placeholders = new ArrayList<String>();
    for (int i = 0; i < ids.size(); i++)
      placeholders.add(":id" + i);
    var query = client.sql(
            "SELECT EntityId, EntityModelId FROM \"{schema}\".\"Entity\" WHERE EntityId IN ({ids})"
                .replace("{schema}", schema)
                .replace("{ids}", String.join(",", placeholders))
        )
        .name("Backoffice.Entities");
    for (int i = 0; i < ids.size(); i++)
      query = query.bind("id" + i, ids.get(i));
    return query.map(row -> entity(row.get("EntityId", UUID.class), row.get("EntityModelId", UUID.class)))
        .all()
        .collect(LinkedHashMap::new, (map, entity) -> map.put(entity.id(), entity));
  }

  /// Everything recorded about the entity. Completes empty if there is no such entity.
  Mono<View> view(UUID entityId) {
    return entity(entityId).flatMap(entity -> Mono.zip(
        events("Event", entityId).collectList(),
        events("IdEvent", entityId).collectList(),
        events("IndexEvent", entityId).collectList(),
        delayed(entityId).map(Optional::of).defaultIfEmpty(Optional.empty()),
        conditionals(entityId).collectList(),
        referrers(entityId).collectList()
    ).map(t -> new View(entity, t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6())));
  }

  /// The latest entities of an entity model, created before the entity `before` if it is given. Entity ids are time
  /// ordered, so the latest have the greatest entity ids.
  Flux<Listed> latest(EntityModel model, UUID before, int limit) {
    var query = client.sql(
            """
            SELECT e.EntityId, ev.Timestamp, ev.Type FROM "{schema}"."Entity" e
            JOIN "{schema}"."Event" ev ON ev.EntityId=e.EntityId AND ev.EventNumber=1
            WHERE e.EntityModelId=:model {before}
            ORDER BY e.EntityId DESC
            OFFSET 0 ROWS FETCH NEXT {limit} ROWS ONLY
            """
                .replace("{schema}", schema)
                .replace("{before}", before != null ? "AND e.EntityId<:before" : "")
                .replace("{limit}", Integer.toString(limit))
        )
        .name("Backoffice.Latest")
        .bind("model", model.id());
    if (before != null)
      query = query.bind("before", before);
    return query.map(row -> new Listed(
            new Entity(row.get("EntityId", UUID.class), model.id(), model),
            row.get("Timestamp", LocalDateTime.class),
            typeName(row.get("Type", UUID.class))
        ))
        .all();
  }

  /// The entities found by a text: the entity with it as entity id, the entities with it as an id and the entities
  /// indexed by it as key
  Flux<Match> search(String text) {
    UUID uuid = uuid(text);
    var matches = new ArrayList<Flux<Tuple>>();
    if (uuid != null)
      matches.add(Flux.just(new Tuple(uuid, "Entity id")));
    for (EventType<?, ?> type : searchableIdTypes) {
      byte[] key = key(type, text, uuid);
      if (key != null)
        matches.add(entityIdsByKey("IdEvent", type, key).map(id -> new Tuple(id, type.name())));
    }
    for (EventType<?, ?> type : searchableIndexTypes)
      matches.add(entityIdsByKey("IndexEvent", type, DataType.string().marshal(text)).map(id -> new Tuple(id, type.name())));
    if (uuid != null) {
      for (EventType<?, ?> type : referenceTypes)
        matches.add(entityIdsByKey("IndexEvent", type, DataType.uuid().marshal(uuid)).map(id -> new Tuple(id, type.name())));
    }
    return Flux.concat(matches)
        .take(maxLookup)
        .collectList()
        .flatMapMany(found -> entities(found.stream().map(Tuple::entityId).toList())
            .flatMapMany(entities -> Flux.fromIterable(found)
                .filter(t -> entities.containsKey(t.entityId()))
                .map(t -> new Match(entities.get(t.entityId()), t.matchedBy()))));
  }

  private record Tuple(UUID entityId, String matchedBy) {}

  /// The key of an id event type for a text, or null if the text can't be one
  private static byte[] key(EventType<?, ?> type, String text, UUID uuid) {
    if (type.outputDataType() instanceof DataType.UUIDDataType)
      return uuid != null ? DataType.uuid().marshal(uuid) : null;
    return DataType.string().marshal(text);
  }

  private Flux<UUID> entityIdsByKey(String table, EventType<?, ?> type, byte[] key) {
    return client.sql(
            """
            SELECT EntityId FROM "{schema}"."{table}" WHERE Type=:type AND Data=:key
            ORDER BY EntityId DESC
            OFFSET 0 ROWS FETCH NEXT {limit} ROWS ONLY
            """
                .replace("{schema}", schema)
                .replace("{table}", table)
                .replace("{limit}", Integer.toString(maxLookup))
        )
        .name("Backoffice.EntityIdsBy" + table)
        .bind("type", type.id())
        .bind("key", key)
        .map(row -> row.get("EntityId", UUID.class))
        .all()
        .distinct();
  }

  /// The entities referring to the entity by an index event with its entity id as key
  private Flux<Referrer> referrers(UUID entityId) {
    byte[] key = DataType.uuid().marshal(entityId);
    return Flux.fromIterable(referenceTypes)
        .concatMap(type -> entityIdsByKey("IndexEvent", type, key).map(id -> new Tuple(id, type.name())))
        .collectList()
        .flatMapMany(found -> entities(found.stream().map(Tuple::entityId).toList())
            .flatMapMany(entities -> Flux.fromIterable(found)
                .filter(t -> entities.containsKey(t.entityId()))
                .map(t -> new Referrer(entities.get(t.entityId()), t.matchedBy()))));
  }

  private Flux<StoredEvent> events(String table, UUID entityId) {
    return client.sql(
            "SELECT EventNumber, Type, Data, Timestamp FROM \"{schema}\".\"{table}\" WHERE EntityId=:entityId ORDER BY EventNumber"
                .replace("{schema}", schema)
                .replace("{table}", table)
        )
        .name("Backoffice." + table)
        .bind("entityId", entityId)
        .map(row -> storedEvent(
            row.get("EventNumber", Integer.class),
            row.get("Type", UUID.class),
            row.get("Data", byte[].class),
            row.get("Timestamp", LocalDateTime.class)
        ))
        .all();
  }

  /// The event the entity is waiting for, unless the entity has had another event since it was scheduled
  private Mono<Delayed> delayed(UUID entityId) {
    return client.sql(
            """
            SELECT EventNumber, Type, Data, Deadline, Attempt FROM "{schema}"."DelayedTrigger"
            WHERE EntityId=:entityId
            AND EventNumber=(SELECT COALESCE(MAX(EventNumber), 0) + 1 FROM "{schema}"."Event" WHERE EntityId=:entityId)
            """.replace("{schema}", schema)
        )
        .name("Backoffice.DelayedTrigger")
        .bind("entityId", entityId)
        .map(row -> {
          int eventNumber = row.get("EventNumber", Integer.class);
          return new Delayed(
              eventNumber,
              storedEvent(eventNumber, row.get("Type", UUID.class), row.get("Data", byte[].class), null),
              row.get("Deadline", LocalDateTime.class),
              row.get("Attempt", Integer.class)
          );
        })
        .one();
  }

  private Flux<Conditional> conditionals(UUID entityId) {
    return client.sql(
            """
            SELECT Type, Data, Timestamp, LastTypes, ReferenceType, IndexKey, TriggerEntityId, TriggerEventNumber
            FROM "{schema}"."ConditionalTrigger"
            WHERE EntityId=:entityId
            ORDER BY Timestamp, Id
            """.replace("{schema}", schema)
        )
        .name("Backoffice.ConditionalTriggers")
        .bind("entityId", entityId)
        .map(row -> new Conditional(
            storedEvent(0, row.get("Type", UUID.class), row.get("Data", byte[].class), row.get("Timestamp", LocalDateTime.class)),
            List.of(row.get("LastTypes", String.class).split(",")).stream().map(id -> typeName(uuid(id))).toList(),
            typeName(row.get("ReferenceType", UUID.class)),
            row.get("IndexKey", byte[].class),
            row.get("TriggerEntityId", UUID.class),
            row.get("TriggerEventNumber", Integer.class)
        ))
        .all();
  }

  private StoredEvent storedEvent(int number, UUID typeId, byte[] data, LocalDateTime timestamp) {
    return new StoredEvent(number, typeId, eventTypeMapper.apply(typeId), data, timestamp);
  }

  private Entity entity(UUID entityId, UUID modelId) {
    return new Entity(entityId, modelId, entityModels.get(modelId));
  }

  private String typeName(UUID typeId) {
    if (typeId == null)
      return "";
    EventType<?, ?> type = eventTypeMapper.apply(typeId);
    return type != null ? type.name() : typeId.toString();
  }

  /// The UUID a text is, or null
  static UUID uuid(String text) {
    if (text == null || text.length() != 36)
      return null;
    try {
      return UUID.fromString(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

}
