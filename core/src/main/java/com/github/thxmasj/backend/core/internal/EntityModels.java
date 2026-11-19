package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// The entity models a state machine is given
public final class EntityModels {

  private EntityModels() {}

  /// The entity models, checked to be models with entities of their own, each given once and with an id of its own
  public static List<EntityModel> validated(List<? extends EntityModel> entityModels) {
    Map<UUID, EntityModel> byId = new HashMap<>();
    for (EntityModel model : entityModels) {
      if (model instanceof EntityModelGroup)
        throw new IllegalArgumentException("Entity model group " + model.name() + " has no entities of its own");
      EntityModel other = byId.putIfAbsent(model.id(), model);
      if (other != null)
        throw new IllegalArgumentException(other == model
            ? "Entity model " + model.name() + " is given more than once"
            : "Entity models " + other.name() + " and " + model.name() + " have the same id " + model.id());
    }
    return List.copyOf(entityModels);
  }
}
