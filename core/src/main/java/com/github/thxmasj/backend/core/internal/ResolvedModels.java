package com.github.thxmasj.backend.core.internal;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EventType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        resolved.stream().collect(toMap(EntityModel::id, identity())),
        Map.copyOf(EventTypes.eventTypesByEntityModel(resolved)),
        EventTypes.eventTypes(resolved)
    );
  }

  /// The model run with the id of the given one, which may be another instance of it, e.g. of a model created from
  /// configuration again by a test. Empty if no model run has the id, or the given one is a group, which isn't run.
  public Optional<EntityModel> run(EntityModel entityModel) {
    if (entityModel instanceof EntityModelGroup)
      return Optional.empty();
    return Optional.ofNullable(entityModelsById.get(entityModel.id()));
  }
}
