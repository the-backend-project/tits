package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.EntityModel;

public class EntityGroupNotInitialised extends RuntimeException {

  private final EntityModel entityType;
  private final Object entityGroup;

  public EntityGroupNotInitialised(EntityModel entityType, Object group) {
    super("Entity group not initialised: " + entityType + "/" + group);
    this.entityType = entityType;
    this.entityGroup = group;
  }

  public EntityModel entityType() {
    return entityType;
  }

  public Object group() {
    return entityGroup;
  }

}
