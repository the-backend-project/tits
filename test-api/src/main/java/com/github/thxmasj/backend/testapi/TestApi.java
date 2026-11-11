package com.github.thxmasj.backend.testapi;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GroupedDataType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import com.github.thxmasj.backend.core.database.ChangeState;
import com.github.thxmasj.backend.core.database.ChangeState.Change;
import com.github.thxmasj.backend.core.database.EntitiesByIndex;
import com.github.thxmasj.backend.core.database.EventsByEntityId;
import com.github.thxmasj.backend.core.database.EventsByIdEvent;
import com.github.thxmasj.backend.core.database.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.database.Mappers;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// The API tests use to look into and manipulate the database of a state machine, instead of querying its tables
/// directly. It knows the entity models only through the transitions given to the state machine, so it works with any
/// of them, and the tests using it do not depend on how the data is stored.
///
/// Some of its operations bypass the transitions, e.g. by appending events that no transition has accepted, and are
/// meant for putting entities in a specific state before a test. It is therefore not to be used in production code.
public class TestApi {

  private final Client client;
  private final String schema;
  private final Clock clock;
  private final Map<UUID, EntityModel> entityModels;
  private final Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel;
  private final Function<UUID, EventType<?, ?>> eventTypeMapper;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByIdEvent eventsByIdEvent;
  private final EventsByLastIndexEvent eventsByLastIndexEvent;
  private final EntitiesByIndex entitiesByIndex;
  private final ChangeState changeState;
  /// Whether a delayed trigger is still pending, i.e. its event number follows the entity's last event
  private final String pending;

  /// @param transitions the transitions of the state machine, which give the entity models and event types
  public TestApi(
      DataSource dataSource,
      String schemaName,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      Clock clock
  ) {
    this.client = new JDBCClient(dataSource);
    this.schema = schemaName;
    this.clock = clock;
    this.entityModels = transitions.keySet().stream().collect(toMap(EntityModel::id, identity()));
    this.eventTypesByEntityModel = Mappers.eventTypesByEntityModel(transitions);
    List<EventType<?, ?>> eventTypes = Mappers.eventTypes(transitions);
    this.eventTypeMapper = Mappers.eventTypeMapper(eventTypes);
    Storage storage = Storage.of(dataSource, transitions, schemaName, clock);
    this.eventsByEntityId = storage.eventsByEntityId();
    this.eventsByIdEvent = storage.eventsByIdEvent();
    this.eventsByLastIndexEvent = storage.eventsByLastIndexEvent();
    this.entitiesByIndex = storage.entitiesByIndex();
    this.changeState = storage.changeState();
    // The queries of the test API quote identifiers and use OFFSET/FETCH, so that they work in both SQL Server and
    // PostgreSQL
    this.pending = "EventNumber=(SELECT COALESCE(MAX(EventNumber), 0) + 1 FROM \"{schema}\".\"Event\" WHERE EntityId=:entityId)";
  }

  /// The event log of an entity, whose entity model is the one it was created with. Completes empty if there is no such
  /// entity. Id and index entities are not found this way, as they have no entity model of their own stored; use
  /// [#eventLog(EntityModel, EntityId)] for those.
  public Mono<EventLog> eventLog(EntityId entityId) {
    return client.sql("SELECT EntityModelId FROM \"{schema}\".\"Entity\" WHERE EntityId=:entityId".replace("{schema}", schema))
        .name("TestApi.EntityModel")
        .bind("entityId", entityId.value())
        .map(row -> row.get("EntityModelId", UUID.class))
        .one()
        .flatMap(modelId -> eventLog(entityModel(modelId), entityId));
  }

  /// The event log of an entity of an entity model, including id and index entities. Completes empty if the entity has
  /// no events.
  public Mono<EventLog> eventLog(EntityModel entityModel, EntityId entityId) {
    return eventsByEntityId.execute(entityModel, eventTypes(entityModel), entityId)
        .onErrorResume(UnknownEntity.class, _ -> Mono.empty());
  }

  /// The event log of the entity having an id, e.g. a merchant id. Completes empty if no entity has the id.
  public <T> Mono<EventLog> eventLogById(EventType<?, T> idType, T id) {
    return eventsByIdEvent.execute(idType, idType.outputDataType().marshal(id));
  }

  /// The last id of a type of an entity. Completes empty if the entity has no such id.
  public <T> Mono<T> id(EntityId entityId, EventType<?, T> idType) {
    return client.sql(
            """
            SELECT Data FROM "{schema}"."IdEvent"
            WHERE EntityId=:entityId AND Type=:type
            ORDER BY EventNumber DESC
            OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY
            """.replace("{schema}", schema)
        )
        .name("TestApi.Id")
        .bind("entityId", entityId.value())
        .bind("type", idType.id())
        .map(row -> idType.outputDataType().unmarshal(row.get("Data", byte[].class)))
        .one();
  }

  /// The event logs of the entities indexed by a key, in the order of their entity ids
  public <T> Flux<EventLog> entitiesByIndex(EventType<?, T> indexType, T key) {
    return entitiesByIndex.execute(indexType.outputDataType().marshal(key), indexType);
  }

  /// The event log of the last entity of a group, e.g. the current settlement of a merchant, i.e. the entity whose
  /// index event of the type has the greatest data of the group. Completes empty if the group has no entities.
  ///
  /// @param entityModel the entity model of the indexed entities
  /// @param indexType an event type whose output data type is a [GroupedDataType] with groups of type `G`
  @SuppressWarnings("unchecked")
  public <G> Mono<EventLog> lastIndexed(EntityModel entityModel, EventType<?, ?> indexType, G group) {
    if (!(indexType.outputDataType() instanceof GroupedDataType<?, ?> dataType))
      return Mono.error(new IllegalArgumentException("Data type of index event type " + indexType.name() + " is not grouped"));
    byte[] marshalledGroup = ((GroupedDataType<?, G>) dataType).marshalGroup(group);
    return eventsByLastIndexEvent.execute(entityModel, eventTypes(entityModel), indexType.id(), marshalledGroup, 1);
  }

  /// An event an entity is waiting for, triggered when the deadline has passed if the entity has no other event before
  /// that
  ///
  /// @param eventNumber the number the event will get
  /// @param attempt the number of times the event has been attempted, which is incremented as an attempt is started
  public record ScheduledEvent(
      EntityId entityId,
      EntityModel entityModel,
      int eventNumber,
      EventType<?, ?> type,
      LocalDateTime deadline,
      int attempt
  ) {}

  /// The event the entity is waiting for. Completes empty if there is none, also if the entity has had another event
  /// since the event was scheduled, as it will then never be triggered.
  public Mono<ScheduledEvent> delayedTrigger(EntityId entityId) {
    return client.sql(
            """
            SELECT EntityModelId, EventNumber, Type, Deadline, Attempt
            FROM "{schema}"."DelayedTrigger"
            WHERE EntityId=:entityId AND {pending}
            """.replace("{pending}", pending).replace("{schema}", schema)
        )
        .name("TestApi.DelayedTrigger")
        .bind("entityId", entityId.value())
        .map(row -> new ScheduledEvent(
            entityId,
            entityModel(row.get("EntityModelId", UUID.class)),
            row.get("EventNumber", Integer.class),
            eventType(row.get("Type", UUID.class)),
            row.get("Deadline", LocalDateTime.class),
            row.get("Attempt", Integer.class)
        ))
        .one();
  }

  /// An event an entity is waiting for, triggered when the entities indexed by the key with the reference type all have
  /// a last event of one of the last types
  ///
  /// @param triggerEntityId the entity whose event created the trigger
  /// @param triggerEventNumber the number of the event that created the trigger
  public record PendingConditionalTrigger(
      UUID id,
      EntityId entityId,
      EntityModel entityModel,
      EventType<?, ?> type,
      List<EventType<?, ?>> lastTypes,
      EventType<?, ?> referenceType,
      byte[] indexKey,
      EntityId triggerEntityId,
      int triggerEventNumber
  ) {}

  /// The events the entity is waiting for on conditions, in the order they were created
  public Flux<PendingConditionalTrigger> conditionalTriggers(EntityId entityId) {
    return client.sql(
            """
            SELECT Id, EntityModelId, Type, LastTypes, ReferenceType, IndexKey, TriggerEntityId, TriggerEventNumber
            FROM "{schema}"."ConditionalTrigger"
            WHERE EntityId=:entityId
            ORDER BY Timestamp, Id
            """.replace("{schema}", schema)
        )
        .name("TestApi.ConditionalTriggers")
        .bind("entityId", entityId.value())
        .map(row -> new PendingConditionalTrigger(
            row.get("Id", UUID.class),
            entityId,
            entityModel(row.get("EntityModelId", UUID.class)),
            eventType(row.get("Type", UUID.class)),
            Arrays.stream(row.get("LastTypes", String.class).split(","))
                .map(UUID::fromString)
                .<EventType<?, ?>>map(this::eventType)
                .toList(),
            eventType(row.get("ReferenceType", UUID.class)),
            row.get("IndexKey", byte[].class),
            new EntityId.UUID(row.get("TriggerEntityId", UUID.class)),
            row.get("TriggerEventNumber", Integer.class)
        ))
        .all();
  }

  /// Appends an event to the event log of an entity without any transition, creating the entity if it has no events.
  /// The event is stored as if a transition had accepted it, so the event the entity was waiting for is no longer
  /// triggered, but it triggers no other events and is not validated against the entity's state. For id and index entities the event
  /// is an id or index event, so an id must not be taken by another entity.
  public <T> Mono<Event<T>> append(EntityModel entityModel, EntityId entityId, EventType<?, T> type, T data) {
    String table = switch (entityModel) {
      case IdEntityModel _ -> "IdEvent";
      case IndexEntityModel _ -> "IndexEvent";
      default -> "Event";
    };
    return client.sql(
            "SELECT COALESCE(MAX(EventNumber), 0) + 1 AS EventNumber FROM \"{schema}\".\"{table}\" WHERE EntityId=:entityId"
                .replace("{schema}", schema)
                .replace("{table}", table)
        )
        .name("TestApi.NextEventNumber")
        .bind("entityId", entityId.value())
        .map(row -> row.get("EventNumber", Integer.class))
        .one()
        .map(eventNumber -> new Event<>(entityId.value(), eventNumber, type, clock, data))
        .flatMap(event -> changeState.execute(ZonedDateTime.now(clock), UUID.randomUUID().toString(), List.of(change(entityModel, entityId, event)))
            .then(Mono.just(event)));
  }

  /// Makes the event the entity is waiting for due now, so that it is triggered without waiting for its deadline.
  /// Completes with whether the entity was waiting for an event.
  public Mono<Boolean> expireDelayedTrigger(EntityId entityId) {
    return client.sql(
            "UPDATE \"{schema}\".\"DelayedTrigger\" SET Deadline=:now WHERE EntityId=:entityId AND {pending}"
                .replace("{pending}", pending)
                .replace("{schema}", schema)
        )
        .name("TestApi.ExpireDelayedTrigger")
        .bind("now", LocalDateTime.now(clock))
        .bind("entityId", entityId.value())
        .update()
        .map(count -> count > 0);
  }

  /// Cancels the events all entities are waiting for, e.g. to start a test with no pending work from earlier tests.
  /// Completes with the number of events cancelled.
  public Mono<Integer> deleteDelayedTriggers() {
    return client.sql("DELETE FROM \"{schema}\".\"DelayedTrigger\"".replace("{schema}", schema))
        .name("TestApi.DeleteDelayedTriggers")
        .update();
  }

  private EntityModel entityModel(UUID id) {
    EntityModel entityModel = entityModels.get(id);
    if (entityModel == null)
      throw new IllegalStateException("Unknown entity model " + id + ". Is it in the transitions given to the test API?");
    return entityModel;
  }

  private List<EventType<?, ?>> eventTypes(EntityModel entityModel) {
    List<EventType<?, ?>> eventTypes = eventTypesByEntityModel.get(entityModel);
    if (eventTypes == null)
      throw new IllegalArgumentException("Unknown entity model " + entityModel.name() + ". Is it in the transitions given to the test API?");
    return eventTypes;
  }

  private EventType<?, ?> eventType(UUID id) {
    EventType<?, ?> eventType = eventTypeMapper.apply(id);
    if (eventType == null)
      throw new IllegalStateException("Unknown event type " + id + ". Is it in the transitions given to the test API?");
    return eventType;
  }

  private static Change change(EntityModel entityModel, EntityId entityId, Event<?> event) {
    return new Change() {
      @Override public EntityModel entityModel() {return entityModel;}
      @Override public EntityId entityId() {return entityId;}
      @Override public Event<?> newEvent() {return event;}
      @Override public DelayedEvent<?> delayedEvent() {return null;}
    };
  }

}
