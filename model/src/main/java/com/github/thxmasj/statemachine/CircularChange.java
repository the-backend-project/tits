package com.github.thxmasj.statemachine;

public class CircularChange extends RuntimeException {

  private final EntityModel entityModel;
  private final EntityId entityId;

  public CircularChange(InputEvent<?> inputEvent, EntityModel entityModel, EntityId entityId) {
    super(String.format(
        "%s on %s/id=%s is circular",
        inputEvent.eventType().name(),
        entityModel.name(),
        entityId.value().toString()
    ));
    this.entityModel = entityModel;
    this.entityId = entityId;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

  public EntityId entityId() {
    return entityId;
  }
}
