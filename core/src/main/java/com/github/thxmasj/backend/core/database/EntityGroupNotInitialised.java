package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.SecondaryIdModel;

public class EntityGroupNotInitialised extends RuntimeException {

  private final EntityModel entityType;
  private final Object entityGroup;

  public EntityGroupNotInitialised(EntityModel entityType, SecondaryIdModel<?> idModel, Object group) {
    super("Identity group " + group + " not initialised on " + entityType + " for id model " + idModel.name());
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
