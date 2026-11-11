package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;

/// No entity is indexed by the key
public class EntityGroupNotInitialised extends RuntimeException {

  public EntityGroupNotInitialised(EntityModel entityModel, EventType<?, ?> indexType, Object key) {
    super("No " + entityModel.name() + " indexed by " + indexType.name() + "=" + key);
  }

}
