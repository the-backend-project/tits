package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.statusOn;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Tuples.Tuple2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Traverser {

  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  public Traverser(Map<State, List<TransitionModel<?, ?>>> transitions) {
    this.transitions = transitions;
    transitions.forEach(this::requireUniquePaths);
  }

  /// Only the event of a chosen transition is stored, so a logged event is matched with the transitions from a state
  /// with the choices expanded, taking the first match. Of the transition matched, the log only uses its target state,
  /// to find the current state, and its reverse model, to roll the event back. Transitions on the same event type, from
  /// the state or chosen from it, are rejected if those differ, since the log couldn't tell which one was taken.
  private void requireUniquePaths(State from, List<TransitionModel<?, ?>> transitionsFromState) {
    var byEventType = new HashMap<EventType<?, ?>, TransitionModel<?, ?>>();
    for (var transition : transitionsFromState) {
      for (var leaf : leaves(transition)) {
        var other = byEventType.putIfAbsent(leaf.eventType(), leaf);
        if (other != null && isDistinguishable(other, leaf)) {
          throw new IllegalArgumentException(String.format(
              "Event type %s has transitions from %s with different target states or reverse models: %s and %s. " +
                  "The event of a chosen transition is stored, so the transition of a logged event would be ambiguous",
              leaf.eventType().name(),
              from.name(),
              other,
              leaf
          ));
        }
      }
    }
  }

  /// Whether the log would have to tell the transitions apart: by their target states, or by their reverse models, which
  /// are compared by identity since separately built reverse models can't be compared
  private static boolean isDistinguishable(TransitionModel<?, ?> a, TransitionModel<?, ?> b) {
    return !Objects.equals(a.toState(), b.toState()) || a.reverseModel() != b.reverseModel();
  }

  public State currentState(EventLog eventLog) {
    var effectiveEvents = eventLog.effectiveEvents();
    State currentState = Begin;
    if (effectiveEvents.isEmpty()) {
      return currentState;
    }
    TransitionModel<?, ?> lastTransition;
    for (var loggedEvent : effectiveEvents.stream().map(Event::type).toList()) {
      lastTransition = findTransitionForLoggedEvent(loggedEvent, currentState);
      currentState = targetState(currentState, lastTransition);
    }
//    System.out.printf(
//        """
//        Traverser.currentState for %s: %s
//        Effective events: %s
//        Actual events:    %s
//        """,
//        eventLog.entityModel().name(),
//        currentState.name(),
//        effectiveEvents.stream().map(e -> e.type().name() + "/" + e.type().id()).collect(joining(", ")),
//        eventLog.events().stream().map(Event::typeName).collect(joining(", "))
//    );
    return currentState;
  }

  private State targetState(State currentState, TransitionModel<?, ?> transition) {
    return transition.toState() != null ? transition.toState() : currentState; // toSelf
  }

//  public State targetState(State currentState, EventType<?, ?> eventType) {
//    var model = accept(currentState, eventType);
//    if (model == null) throw new IllegalStateException("No transition found for event " + eventType);
//    return targetState(currentState, model);
//  }

  public Tuple2<State, TransitionModel<?, ?>> accept(EventLog eventLog, EventType<?, ?> eventType) {
    var currentState = currentState(eventLog);
    var availableTransitions = transitions.get(currentState);
    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState.name() + " on " + eventLog.entityModel().name());
    var t = findTransitionForTriggeredEvent(eventType, availableTransitions);
    if (t != null && eventType == BuiltinEventTypes.EventsRolledBack)
      System.out.println("Using custom rollback transition for entity " + eventLog.entityModel().name() + ": " + t + "\nAll transitions:\n" + transitions.values().stream().flatMap(List::stream).map(Object::toString).collect(joining("\n")));
    if (t == null && eventType == BuiltinEventTypes.EventsRolledBack && eventType instanceof BasicEventType.Rollback rollback)
      t = rollbackOn(rollback);
    else if (t == null && eventType == BuiltinEventTypes.StatusLookedUp)
      t = statusOn();
    return tuple(currentState, t);
  }

  public State targetState(EventType<?, ?>... eventTypes) {
    var currentState = Begin;
    for (var eventType : eventTypes) {
      var t = findTransitionForLoggedEvent(eventType, currentState);
      currentState = t.toState();
    }
    return currentState;
  }

//  public TransitionModel<?, ?> accept(State currentState, EventType<?, ?> eventType) {
//    var availableTransitions = transitions.get(currentState);
//    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState);
//    var t = findTransition(eventType, availableTransitions);
//    if (t == null && eventType == BuiltinEventTypes.Rollback && eventType instanceof BasicEventType.Rollback rollback)
//      t = onEvent(rollback).toSelf().assembleInput().output(d -> d);
//    return t;
//  }

  public TransitionModel<?, ?> transitionForEventNumber(EventLog eventLog, int eventNumber) {
    var events = eventLog.events();
    if (events.isEmpty()) {
      throw new IllegalStateException("Event log is empty");
    }
    State state = Begin;
    for (var event : events) {
      if (event.eventNumber() == eventNumber) {
        return findTransitionForLoggedEvent(event.type(), state);
      }
      state = targetStateForLoggedEvent(state, event.type());
    }
    throw new IllegalStateException("Event number " + eventNumber + " not found in event log");
  }

  private TransitionModel<?, ?> modelForLoggedEvent(State currentState, EventType<?, ?> eventType) {
    return findTransitionForLoggedEvent(eventType, currentState);
  }

  private State targetStateForLoggedEvent(State currentState, EventType<?, ?> eventType) {
    return targetState(currentState, modelForLoggedEvent(currentState, eventType));
  }


  private TransitionModel<?, ?> findTransitionForTriggeredEvent(EventType<?, ?> eventType, List<TransitionModel<?, ?>> transitions) {
    for (var transition : transitions) {
      if (transition.eventType().equals(eventType)) {
        return transition;
      }
    }
    return null;
  }

  private TransitionModel<?, ?> findTransitionForLoggedEvent(EventType<?, ?> eventType, State fromState) {
    for (var transition : transitions.get(fromState)) {
      for (var leafTransition : leaves(transition)) {
        if (leafTransition.eventType().equals(eventType)) {
          return leafTransition;
        }
      }
    }
    // TODO: Avoid dynamic creation
    if (eventType == BuiltinEventTypes.EventsRolledBack && eventType instanceof BasicEventType.Rollback rollback)
      return rollbackOn(rollback);
    throw new IllegalStateException(
        "No transition found for logged event " + eventType.name() + " from " + fromState.name() + " (available: " + transitions.get(fromState).stream()
            .flatMap(t -> leaves(t).stream())
            .map(t -> t.eventType().name())
            .collect(joining(", ")) + ")");
  }

  private List<TransitionModel<?, ?>> leaves(TransitionModel<?, ?> transition) {
    ArrayList<TransitionModel<?, ?>> leaves = new ArrayList<>();
    if (transition.filters().isEmpty()) {
      // transition is a leaf
      leaves.add(transition);
    } else {
      transition.filters().stream().map(f -> f.alternative().model()).forEach(alternativeModel -> leaves.addAll(leaves(alternativeModel)));
    }
    return unmodifiableList(leaves);
  }



}
