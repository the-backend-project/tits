package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.statusOn;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Traverser {

  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  public Traverser(Map<State, List<TransitionModel<?, ?>>> transitions) {this.transitions = transitions;}

  public State currentState(EventLog eventLog) {
    var effectiveEvents = eventLog.effectiveEvents();
//    System.out.printf(
//        """
//        Traverser.currentState:
//        Effective events: %s
//        Actual events:    %s
//        """,
//        effectiveEvents.stream().map(Event::typeName).collect(joining(", ")),
//        eventLog.events().stream().map(Event::typeName).collect(joining(", "))
//    );
    State currentState = eventLog.entityModel().initialState();
    if (effectiveEvents.isEmpty()) {
      return currentState;
    }
    TransitionModel<?, ?> lastTransition;
    for (var loggedEvent : effectiveEvents.stream().map(Event::type).toList()) {
      lastTransition = findTransitionForLoggedEvent(loggedEvent, currentState);
      currentState = targetState(currentState, lastTransition);
    }
    return currentState;
  }

  public State targetState(State currentState, TransitionModel<?, ?> transition) {
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
    if (availableTransitions == null) throw new IllegalStateException("No available transitions for current state " + currentState + " on " + eventLog.entityModel().name());
    var t = findTransitionForTriggeredEvent(eventType, availableTransitions);
    if (t != null && eventType == BuiltinEventTypes.Rollback)
      System.out.println("Using custom rollback transition for entity " + eventLog.entityModel().name() + ": " + t + "\nAll transitions:\n" + transitions.values().stream().flatMap(List::stream).map(Object::toString).collect(joining("\n")));
    if (t == null && eventType == BuiltinEventTypes.Rollback && eventType instanceof BasicEventType.Rollback rollback)
      //t = onEvent(rollback).toSelf().assembleInput().output(d -> d);
      t = rollbackOn(rollback);
    else if (t == null && eventType == BuiltinEventTypes.Status)
      t = statusOn();
    return tuple(currentState, t);
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
    State state = eventLog.entityModel().initialState();
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
    if (eventType == BuiltinEventTypes.Rollback && eventType instanceof BasicEventType.Rollback rollback)
      return rollbackOn(rollback);
    throw new IllegalStateException(
        "No transition found for logged event " + eventType.name() + " from " + fromState.name() + " (available: " + transitions.get(fromState).stream()
            .flatMap(t -> leaves(t).stream())
            .map(t -> t.eventType().name())
            .collect(joining(", ")) + ")");
  }

  private List<TransitionModel<?, ?>> leaves(TransitionModel<?, ?> transition) {
    ArrayList<TransitionModel<?, ?>> leaves = new ArrayList<>();
    if (transition.filters().isEmpty() && transition.duplicateModels().isEmpty() && transition.rejectModel() == null) {
      // transition is a leaf
      leaves.add(transition);
    } else {
      transition.filters().stream().map(f -> f.alternative().model()).forEach(alternativeModel -> leaves.addAll(leaves(alternativeModel)));
      transition.duplicateModels().forEach(duplicateModel -> leaves.addAll(leaves(duplicateModel)));
      if (transition.rejectModel() != null) leaves.add(transition.rejectModel());
    }
    return unmodifiableList(leaves);
  }



}
