package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryId;

public class UnknownEntity extends RuntimeException {

  private final EntityModel entityModel;
  private final SecondaryId<?> secondaryId;
  private final EntityId id;

  public UnknownEntity(EntityModel entityModel, SecondaryId<?> id) {
    super(String.format("Unknown entity: %s/%s=%s", entityModel.name(), id.model().name(), id));
    this.secondaryId = id;
    this.entityModel = entityModel;
    this.id = null;
  }

  public UnknownEntity(EntityModel entityModel, EntityId id) {
    super(String.format("Unknown entity: %s/id=%s", entityModel.name(), id.value()));
    this.entityModel = entityModel;
    this.secondaryId = null;
    this.id = id;
  }

  public SecondaryId<?> secondaryId() {
    return secondaryId;
  }

  public EntityId id() {
    return id;
  }

}
