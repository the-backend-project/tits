package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.util.UUID;

public class UnknownEntity extends RuntimeException {

  private final EntityModel entityModel;
  private final UUID id;

  public UnknownEntity(EntityModel entityModel, EventType<?, ?> idType, Object id) {
    super(String.format("Unknown entity: %s/%s=%s", entityModel.name(), idType.name(), id));
    this.entityModel = entityModel;
    this.id = null;
  }

  public UnknownEntity(EntityModel entityModel, UUID id) {
    super(String.format("Unknown entity: %s/id=%s", entityModel.name(), id));
    this.entityModel = entityModel;
    this.id = id;
  }

  public UUID id() {
    return id;
  }

  public EntityModel entityModel() {
    return entityModel;
  }

}
