package com.github.thxmasj.backend.testapi;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GroupedDataType;
import com.github.thxmasj.backend.core.database.SqlDatabase;
import com.github.thxmasj.backend.core.database.Store;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.internal.DelayedEvent;
import com.github.thxmasj.backend.core.internal.ResolvedModels;
import com.github.thxmasj.backend.core.internal.database.ChangeState;
import com.github.thxmasj.backend.core.internal.database.ChangeState.Change;
import com.github.thxmasj.backend.core.internal.database.EntitiesByIndex;
import com.github.thxmasj.backend.core.internal.database.EventsByEntityId;
import com.github.thxmasj.backend.core.internal.database.EventsByIdEvent;
import com.github.thxmasj.backend.core.internal.database.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.internal.database.Storage;
import com.github.thxmasj.backend.core.internal.database.StorageProvider;
import com.github.thxmasj.backend.core.internal.database.TestStorage;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/// The API tests use to look into and manipulate the database of a state machine, instead of querying its tables
/// directly. It knows the entity models only as given to the state machine, so it works with any
/// of them, and the tests using it do not depend on how the data is stored.
///
/// Some of its operations bypass the transitions, e.g. by appending events that no transition has accepted, and are
/// meant for putting entities in a specific state before a test. It is therefore not to be used in production code.
public class TestApi {

  private final Clock clock;
  private final ResolvedModels models;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByIdEvent eventsByIdEvent;
  private final EventsByLastIndexEvent eventsByLastIndexEvent;
  private final EntitiesByIndex entitiesByIndex;
  private final ChangeState changeState;
  private final TestStorage testStorage;

  /// The database is the one behind the data source (see [SqlDatabase#of(DataSource)])
  ///
  /// @param entityModels the entity models given to the state machine, which with those their transitions trigger
  ///                     events on give the entity models and event types
  public TestApi(
      DataSource dataSource,
      String schemaName,
      List<? extends EntityModel> entityModels,
      Clock clock
  ) {
    this(SqlDatabase.of(dataSource).client(dataSource), schemaName, entityModels, clock);
  }

  /// @param store a store of the database, e.g. the one of the state machine, whose storage is found by its
  ///              [Store#database()]
  /// @param entityModels the entity models given to the state machine, which with those their transitions trigger
  ///                     events on give the entity models and event types
  public TestApi(
      Store store,
      String schemaName,
      List<? extends EntityModel> entityModels,
      Clock clock
  ) {
    this.clock = clock;
    ResolvedModels models = ResolvedModels.of(entityModels);
    this.models = models;
    StorageProvider provider = StorageProvider.of(store);
    Storage storage = provider.storage(store, models, schemaName, clock);
    this.eventsByEntityId = storage.eventsByEntityId();
    this.eventsByIdEvent = storage.eventsByIdEvent();
    this.eventsByLastIndexEvent = storage.eventsByLastIndexEvent();
    this.entitiesByIndex = storage.entitiesByIndex();
    this.changeState = storage.changeState();
    this.testStorage = provider.testStorage(store, models, schemaName, clock);
  }

  /// The event log of an entity, whose entity model is the one it was created with. Completes empty if there is no such
  /// entity. Id and index entities are not found this way, as they have no entity model of their own stored; use
  /// [#eventLog(EntityModel, UUID)] for those.
  public Mono<EventLog> eventLog(UUID entityId) {
    return testStorage.entityModel(entityId).flatMap(entityModel -> eventLog(entityModel, entityId));
  }

  /// The event log of an entity of an entity model, including id and index entities. Completes empty if the entity has
  /// no events.
  public Mono<EventLog> eventLog(EntityModel entityModel, UUID entityId) {
    if (!models.runs(entityModel))
      return Mono.error(models.notRun(entityModel, "the test API"));
    return eventsByEntityId.execute(entityModel, eventTypes(entityModel), entityId)
        .onErrorResume(UnknownEntity.class, _ -> Mono.empty());
  }

  /// The event log of the entity having an id, e.g. a merchant id. Completes empty if no entity has the id.
  public <T> Mono<EventLog> eventLogById(EventType<?, T> idType, T id) {
    return eventsByIdEvent.execute(idType, idType.outputDataType().marshal(id));
  }

  /// The last id of a type of an entity. Completes empty if the entity has no such id.
  public <T> Mono<T> id(UUID entityId, EventType<?, T> idType) {
    return testStorage.lastId(entityId, idType).map(data -> idType.outputDataType().unmarshal(data));
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
    if (!models.runs(entityModel))
      return Mono.error(models.notRun(entityModel, "the test API"));
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
      UUID entityId,
      EntityModel entityModel,
      int eventNumber,
      EventType<?, ?> type,
      LocalDateTime deadline,
      int attempt
  ) {}

  /// The event the entity is waiting for. Completes empty if there is none, also if the entity has had another event
  /// since the event was scheduled, as it will then never be triggered.
  public Mono<ScheduledEvent> delayedTrigger(UUID entityId) {
    return testStorage.delayedTrigger(entityId)
        .map(trigger -> new ScheduledEvent(
            entityId,
            trigger.entityModel(),
            trigger.eventNumber(),
            trigger.type(),
            trigger.deadline(),
            trigger.attempt()
        ));
  }

  /// An event an entity is waiting for, triggered when the entities indexed by the key with the reference type all have
  /// a last event of one of the last types
  ///
  /// @param triggerEntityId the entity whose event created the trigger
  /// @param triggerEventNumber the number of the event that created the trigger
  public record PendingConditionalTrigger(
      UUID id,
      UUID entityId,
      EntityModel entityModel,
      EventType<?, ?> type,
      List<EventType<?, ?>> lastTypes,
      EventType<?, ?> referenceType,
      byte[] indexKey,
      UUID triggerEntityId,
      int triggerEventNumber
  ) {}

  /// The events the entity is waiting for on conditions, in the order they were created
  public Flux<PendingConditionalTrigger> conditionalTriggers(UUID entityId) {
    return testStorage.conditionalTriggers(entityId)
        .map(trigger -> new PendingConditionalTrigger(
            trigger.id(),
            entityId,
            trigger.entityModel(),
            trigger.type(),
            trigger.lastTypes(),
            trigger.referenceType(),
            DataType.uuid().marshal(trigger.indexKey()),
            trigger.triggerEvent().entityId(),
            trigger.triggerEvent().eventNumber()
        ));
  }

  /// Appends an event to the event log of an entity without any transition, creating the entity if it has no events.
  /// The event is stored as if a transition had accepted it, so the event the entity was waiting for is no longer
  /// triggered, but it triggers no other events and is not validated against the entity's state. For id and index entities the event
  /// is an id or index event, so an id must not be taken by another entity.
  public <T> Mono<Event<T>> append(EntityModel entityModel, UUID entityId, EventType<?, T> type, T data) {
    if (!models.runs(entityModel))
      return Mono.error(models.notRun(entityModel, "the test API"));
    return testStorage.nextEventNumber(entityModel, entityId)
        .map(eventNumber -> new Event<>(entityId, eventNumber, type, clock, data))
        .flatMap(event -> changeState.execute(ZonedDateTime.now(clock), UUID.randomUUID().toString(), List.of(change(entityModel, entityId, event)))
            .then(Mono.just(event)));
  }

  /// Makes the event the entity is waiting for due now, so that it is triggered without waiting for its deadline.
  /// Completes with whether the entity was waiting for an event.
  public Mono<Boolean> expireDelayedTrigger(UUID entityId) {
    return testStorage.expireDelayedTrigger(entityId);
  }

  /// Cancels the events all entities are waiting for, e.g. to start a test with no pending work from earlier tests.
  /// Completes with the number of events cancelled.
  public Mono<Integer> deleteDelayedTriggers() {
    return testStorage.deleteDelayedTriggers();
  }

  private List<EventType<?, ?>> eventTypes(EntityModel entityModel) {
    return models.eventTypesByEntityModel().get(entityModel);
  }

  private static Change change(EntityModel entityModel, UUID entityId, Event<?> event) {
    return new Change() {
      @Override public EntityModel entityModel() {return entityModel;}
      @Override public UUID entityId() {return entityId;}
      @Override public Event<?> newEvent() {return event;}
      @Override public DelayedEvent<?> delayedEvent() {return null;}
    };
  }

}
