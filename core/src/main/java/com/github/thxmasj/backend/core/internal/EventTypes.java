package com.github.thxmasj.backend.core.internal;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.BuiltinEventTypes;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
  public static Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    return transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> eventTypes(transitionsOf(e).values())));
  }

  /// The event types of the transitions of all entity models, including the builtin event types
  public static List<EventType<?, ?>> eventTypes(Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    return eventTypes(transitions.entrySet().stream().flatMap(e -> transitionsOf(e).values().stream()).toList());
  }

  private static Map<State, List<TransitionModel<?, ?>>> transitionsOf(Entry<EntityModel, Map<State, List<TransitionModel<?, ?>>>> entry) {
    return requireNonNull(entry.getValue(), () -> "Entity model " + entry.getKey().name() + " has no transitions");
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
