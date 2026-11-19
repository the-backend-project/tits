package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// The entity models a state machine is given
public final class EntityModels {

  private EntityModels() {}

  /// The entity models, each once, checked to be models with entities of their own, with an id of their own, and to
  /// trigger events only on each other. A model given more than once is kept once, as the entity models of an
  /// application are often put together from parts which share some, e.g. the index of the outbox requests.
  ///
  /// @throws IllegalArgumentException if one is an [EntityModelGroup], if two have the same id, or if a transition
  ///                                  triggers an event on an entity model which is not one of them
  public static List<EntityModel> validated(List<? extends EntityModel> entityModels) {
    Set<EntityModel> models = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<UUID, EntityModel> byId = new HashMap<>();
    List<EntityModel> validated = new ArrayList<>();
    for (EntityModel model : entityModels) {
      if (model instanceof EntityModelGroup)
        throw new IllegalArgumentException("Entity model group " + model.name() + " has no entities of its own");
      if (!models.add(model))
        continue;
      EntityModel other = byId.putIfAbsent(model.id(), model);
      if (other != null)
        throw new IllegalArgumentException(
            "Entity models " + other.name() + " and " + model.name() + " have the same id " + model.id()
        );
      validated.add(model);
    }
    for (EntityModel model : validated)
      for (List<TransitionModel<?, ?>> transitions : model.transitions().values())
        for (TransitionModel<?, ?> transition : transitions)
          checkTargets(model, transition, models, byId);
    return List.copyOf(validated);
  }

  /// Checks that the triggers of the transition, of its reversal and of the transitions it may choose are on the
  /// entity models given, or on groups of them
  private static void checkTargets(
      EntityModel model,
      TransitionModel<?, ?> transition,
      Set<EntityModel> models,
      Map<UUID, EntityModel> byId
  ) {
    checkTargets(model, transition, transition.triggers(), models, byId);
    if (transition.reverseModel() != null)
      checkTargets(model, transition, transition.reverseModel().triggers(), models, byId);
    for (var filter : transition.filters())
      checkTargets(model, filter.alternative().model(), models, byId);
  }

  private static void checkTargets(
      EntityModel model,
      TransitionModel<?, ?> transition,
      List<EventTrigger<?, ?, ?>> triggers,
      Set<EntityModel> models,
      Map<UUID, EntityModel> byId
  ) {
    for (var trigger : triggers) {
      EntityModel target = trigger.entityModel();
      if (target == null || target instanceof EntityModelGroup || models.contains(target))
        continue;
      String message = String.format(
          "%s on %s triggers %s on %s, which is not one of the entity models given to the state machine",
          transition.eventType().name(),
          model.name(),
          trigger.eventSpec().eventType().name(),
          target.name()
      );
      EntityModel sameId = byId.get(target.id());
      throw new IllegalArgumentException(sameId == null ? message : message +
          ". Another entity model with its id is, which the event is to be triggered on instead, e.g. the outbox() of "
          + "the dispatcher of an outbox request");
    }
  }
}
