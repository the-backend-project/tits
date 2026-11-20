package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/// The entity models a state machine runs
public final class EntityModels {

  private EntityModels() {}

  /// The entity models given, and those their transitions trigger events on, and so on, each once. The models given
  /// are those nothing triggers events on, e.g. the entity models of an inbox, whose requests trigger the events of
  /// the application, whose transitions in turn trigger events on e.g. outbox requests, rollback models and indexes.
  /// The models found are in the order they are reached, after the ones given.
  ///
  /// @throws IllegalArgumentException if a model given is an [EntityModelGroup], which has no entities of its own, or
  ///                                  if two models have the same id
  public static List<EntityModel> resolve(List<? extends EntityModel> entityModels) {
    for (EntityModel model : entityModels)
      if (model instanceof EntityModelGroup)
        throw new IllegalArgumentException("Entity model group " + model.name() + " has no entities of its own");
    Set<EntityModel> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Map<UUID, EntityModel> byId = new HashMap<>();
    List<EntityModel> resolved = new ArrayList<>();
    Deque<EntityModel> toVisit = new ArrayDeque<>(entityModels);
    while (!toVisit.isEmpty()) {
      EntityModel model = toVisit.removeFirst();
      if (!seen.add(model))
        continue;
      EntityModel other = byId.putIfAbsent(model.id(), model);
      if (other != null)
        throw new IllegalArgumentException(
            "Entity models " + other.name() + " and " + model.name() + " have the same id " + model.id()
        );
      resolved.add(model);
      for (List<TransitionModel<?, ?>> transitions : model.transitions().values())
        for (TransitionModel<?, ?> transition : transitions)
          addTargets(transition, toVisit);
    }
    return List.copyOf(resolved);
  }

  /// Adds the entity models the transition triggers events on, in its reversal and in the transitions it may choose,
  /// and so on. A group is left out, as its models are those of the group which are run.
  private static void addTargets(TransitionModel<?, ?> transition, Deque<EntityModel> toVisit) {
    addTargets(transition.triggers(), toVisit);
    if (transition.reverseModel() != null)
      addTargets(transition.reverseModel(), toVisit);
    for (var filter : transition.filters())
      addTargets(filter.alternative().model(), toVisit);
  }

  private static void addTargets(List<EventTrigger<?, ?, ?>> triggers, Deque<EntityModel> toVisit) {
    for (var trigger : triggers) {
      EntityModel target = trigger.entityModel();
      if (target != null && !(target instanceof EntityModelGroup))
        toVisit.addLast(target);
    }
  }
}
