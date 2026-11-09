package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;

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
