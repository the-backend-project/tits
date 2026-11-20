package com.github.thxmasj.backend.core.internal;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// The entity models a state machine runs, with their event types, worked out once from the entity models given, for
/// the state machine, its storage, the test API and the backoffice
///
/// @param entityModels            the models given, and those their transitions trigger events on (see
///                                [EntityModel#reachable(List)])
/// @param entityModelsById        the models by their ids
/// @param eventTypesByEntityModel the event types of each model, including the builtin event types
/// @param eventTypes              the event types of all the models, including the builtin event types
public record ResolvedModels(
    List<EntityModel> entityModels,
    Map<UUID, EntityModel> entityModelsById,
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
    List<EventType<?, ?>> eventTypes
) {

  public static ResolvedModels of(List<? extends EntityModel> entityModels) {
    List<EntityModel> resolved = EntityModels.resolve(entityModels);
    return new ResolvedModels(
        resolved,
        Map.copyOf(resolved.stream().collect(toMap(EntityModel::id, identity()))),
        Map.copyOf(EventTypes.eventTypesByEntityModel(resolved)),
        EventTypes.eventTypes(resolved)
    );
  }

  /// Whether the entity model is one of those run, the same instance. Another instance with its id isn't, as it may
  /// differ, e.g. be created from other configuration.
  public boolean runs(EntityModel entityModel) {
    return !(entityModel instanceof EntityModelGroup) && entityModelsById.get(entityModel.id()) == entityModel;
  }

  /// The failure of using an entity model which isn't run, see [#runs(EntityModel)]
  ///
  /// @param runner what runs the models, e.g. "the state machine", for the message
  public IllegalArgumentException notRun(EntityModel entityModel, String runner) {
    EntityModel sameId = entityModelsById.get(entityModel.id());
    return new IllegalArgumentException(sameId == null
        ? "Entity model " + entityModel.name() + " with id " + entityModel.id() + " is not run by " + runner
        : "Entity model " + entityModel.name() + " is another instance than the one with its id run by " + runner +
            ", e.g. as it is created again from configuration. Use the one run, e.g. from the entityModels() of the " +
            "state machine");
  }
}
