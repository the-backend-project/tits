package com.github.thxmasj.statemachine;

import static java.util.stream.Collectors.joining;

public class RejectedEvent extends RuntimeException {

  private final EventType<?, ?> eventType;
  private final EntityModel entityModel;
  private final EntityId entityId;
  private final State currentState;

  private static String prefixMessage(EventType<?, ?> eventType, EntityModel entityModel, EntityId entityId) {
    return String.format(
        "[%s] on [%s]/%s rejected",
        eventType != null ? eventType.name() : "<no event type>",
        entityModel != null ? entityModel.name() : "<no entity model>",
        entityId != null ? entityId.value() : "<no entity id>"
    );
  }

  public RejectedEvent(EventType<?, ?> eventType, EventLog eventLog, State currentState) {
    super(String.format(
        "%s for state [%s] (%s)",
        prefixMessage(eventType, eventLog.entityModel(), eventLog.entityId()),
        currentState != null ? currentState.name() : "<no current state>",
        eventLog.events().stream().map(e -> e.type().name()).collect(joining(", "))
    ));
    this.eventType = eventType;
    this.entityModel = eventLog.entityModel();
    this.entityId = eventLog.entityId();
    this.currentState = currentState;
  }

  public RejectedEvent(
      EventType<?, ?> eventType,
      EntityModel entityModel,
      EntityId entityId,
      State currentState,
      int rollbackTo
  ) {
    super(String.format(
        "%s for state [%s]: Can't rollback to event number %d",
        prefixMessage(eventType, entityModel, entityId),
        currentState != null ? currentState.name() : "<no current state>",
        rollbackTo
    ));
    this.eventType = eventType;
    this.entityModel = entityModel;
    this.entityId = entityId;
    this.currentState = currentState;
  }

  public EventType<?, ?> eventType() {
    return eventType;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  public EntityId entityId() {
    return entityId;
  }
}
