package com.github.thxmasj.backend.core.internal;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.util.List;
import java.util.Map;

/// The entity models a state machine runs, with their event types, worked out once from the entity models given, for
/// the state machine, its storage, the test API and the backoffice
///
/// @param entityModels            the models given, and those their transitions trigger events on (see
///                                [EntityModel#reachable(List)])
/// @param eventTypesByEntityModel the event types of each model, including the builtin event types
/// @param eventTypes              the event types of all the models, including the builtin event types
public record ResolvedModels(
    List<EntityModel> entityModels,
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel,
    List<EventType<?, ?>> eventTypes
) {

  public static ResolvedModels of(List<? extends EntityModel> entityModels) {
    List<EntityModel> resolved = EntityModels.resolve(entityModels);
    return new ResolvedModels(
        resolved,
        Map.copyOf(EventTypes.eventTypesByEntityModel(resolved)),
        EventTypes.eventTypes(resolved)
    );
  }
}
