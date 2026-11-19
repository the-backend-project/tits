package com.github.thxmasj.backend.core.internal;

import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.BuiltinEventTypes;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/// The event types of transitions
public final class EventTypes {

  private EventTypes() {}

  /// The event type of the transition and those of the alternatives of its filters
  public static List<EventType<?, ?>> eventTypesFor(TransitionModel<?, ?> transition) {
    ArrayList<EventType<?, ?>> eventTypes = new ArrayList<>();
    eventTypes.add(transition.eventType());
    transition.filters().stream().map(f -> f.alternative().model()).map(EventTypes::eventTypesFor).forEach(eventTypes::addAll);
    return Collections.unmodifiableList(eventTypes);
  }

  /// The event types of the transitions of each entity model, including the builtin event types
  public static Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel(List<EntityModel> entityModels) {
    return entityModels.stream().collect(toMap(m -> m, m -> eventTypes(m.transitions().values())));
  }

  /// The event types of the transitions of all entity models, including the builtin event types
  public static List<EventType<?, ?>> eventTypes(List<EntityModel> entityModels) {
    return eventTypes(entityModels.stream().flatMap(m -> m.transitions().values().stream()).toList());
  }

  private static List<EventType<?, ?>> eventTypes(Collection<List<TransitionModel<?, ?>>> transitions) {
    return Stream.concat(
            BuiltinEventTypes.ALL.stream(),
            transitions.stream().flatMap(List::stream).flatMap(t -> eventTypesFor(t).stream())
        )
        .distinct()
        .toList();
  }
}
