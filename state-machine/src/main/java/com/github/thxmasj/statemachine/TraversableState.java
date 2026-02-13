package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;

import com.github.thxmasj.statemachine.TransitionModelBuilder.Filter;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class TraversableState {
  private final State state;
  //private final Map<TransitionModel<?, ?>, TraversableState> backwardTransitions;
  private final Map<TransitionModel<?, ?>, TraversableState> forwardTransitions;

  public TraversableState(
      State state,
      //Map<TransitionModel<?, ?>, TraversableState> backwardTransitions,
      Map<TransitionModel<?, ?>, TraversableState> forwardTransitions
  ) {
    this.state = state;
    //this.backwardTransitions = backwardTransitions;
    this.forwardTransitions = forwardTransitions;
  }

  public State state() {
    return state;
  }

  public Set<TransitionModel<?, ?>> forwardTransitions() {
    return forwardTransitions.keySet();
  }

  public static synchronized TraversableState create(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    return create(null, null, transitions, model.initialState(), new HashMap<>());
  }

  private static TraversableState create(
      TraversableState fromState,
      TransitionModel<?, ?> transitionIn,
      Map<State, List<TransitionModel<?, ?>>> transitions,
      State initialState,
      Map<State, TraversableState> visitedStates
  ) {
    boolean initial = visitedStates.isEmpty();
    State state = transitionIn != null ? (transitionIn.toState() != null ? transitionIn.toState() : fromState.state()) : initialState;
    if (visitedStates.containsKey(state)) {
      //visitedStates.get(state).backwardTransitions.put(transitionIn, fromState);
      return visitedStates.get(state);
    }
    Map<TransitionModel<?, ?>, TraversableState> forwardTransitions = new HashMap<>();
    //Map<TransitionModel<?, ?>, TraversableState> backwardTransitions = new HashMap<>();
    TraversableState node = new TraversableState(state, /*backwardTransitions,*/ forwardTransitions);
//    if (transitionIn != null)
//      backwardTransitions.put(transitionIn, fromState);
    visitedStates.put(state, node);
    if (!transitions.containsKey(state))
      throw new IllegalStateException("Transition state not found: " + state);
    for (TransitionModel<?, ?> transition : transitions.get(state)) {
      //if (transition.fromState().equals(state))
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
      for (Filter<?, ?, ?> filter : transition.filters()) {
        forwardTransitions.put(
            filter.alternative().model(),
            create(node, filter.alternative().model(), transitions, initialState, visitedStates)
        );
      }
    }
//    if (initial && forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.UnknownEntity)) {
//      TransitionModel<?, ?> transition = onEvent(BuiltinEventTypes.UnknownEntity)
//          .from(state)
//          .to(state)
//          .trigger(new BadRequest()).with(_ -> "Unknown entity")
//          .output();
//      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
//    }
//    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.InvalidRequest)) {
//      var transition = invalidRequestTransition(state);
//      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
//    }
//    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.RejectedRequest)) {
//      var transition = rejectedRequestTransition(state);
//      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
//    }
    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.Status)) {
      var transition = statusTransition(state);
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    return node;
  }

  private static TransitionModel<?, ?>  statusTransition(State state) {
    return onEvent(BuiltinEventTypes.Status)
        //.from(state)
        .to(state)
        .assemble((_, _) -> state)
        //.trigger(response(new OK()))
        .output(d -> d);
  }

//  private static TransitionModel<?, ?> invalidRequestTransition(State state) {
//    return builtinTransition(state, BuiltinEventTypes.InvalidRequest, new BadRequest());
//  }
//
//  private static TransitionModel<?, ?> rejectedRequestTransition(State state) {
//    return builtinTransition(state, BuiltinEventTypes.RejectedRequest, new UnprocessableEntity());
//  }
//
//  private static TransitionModel<?, ?> builtinTransition(State state, EventType<String, ?> eventType, OutgoingResponseCreator<String> response) {
//    return onEvent(eventType)
//        //.from(state)
//        .to(state)
//        .assemble((input, _) -> input.data())
//        .trigger(response(response))
//        .output();
//  }

  public Collection<TraversableState> targetStates() {
    return forwardTransitions.values();
  }

  public <I, O> TransitionModel<I, O> transition(EventType<I, O> eventType) {
    if (eventType instanceof BasicEventType.Rollback) {
      return onEvent(eventType)/*.from(state)*/.toSelf().assembleInput().output();
    }
    for (var transition : forwardTransitions.keySet()) {
      if (transition.eventType() == eventType)
        return (TransitionModel<I, O>)transition; // TODO
      else {
        var t = transition.filters().stream()
            .map(f -> f.alternative().model())
            .filter(model -> model.eventType() == eventType)
            .findFirst();
        if (t.isPresent()) return (TransitionModel<I, O>)t.get();
      }
    }
    return null;
  }

//  public TraversableState backward(EventType<?, ?> eventType) {
//    return backwardTransitions.entrySet()
//        .stream()
//        .filter(e -> e.getKey().eventType() == eventType)
//        .map(Map.Entry::getValue)
//        .findFirst()
//        .orElse(null);
//  }

  public TraversableState forward(EventType<?, ?> eventType) {
    return forward(List.of(eventType));
  }

  public TraversableState forward(List<? extends EventType<?, ?>> eventTypes) {
    var traverser = this;
    for (var eventType : eventTypes) {
//      if (eventType.isRollback()) {
//
//      }
      TransitionModel<?, ?> transition = traverser.transition(eventType);
      if (transition == null) return null;
      var nextTraverser = traverser.forwardTransitions.get(transition);
      if (nextTraverser == null) throw new IllegalStateException(String.format(
          "Traversing from %s with %s: Missing traverser for valid transition (%s). Got:" + traverser.forwardTransitions.entrySet()
              .stream().collect(Collectors.toMap(Entry::getKey, Entry::getValue)),
          state,
          eventTypes.stream().map(EventType::name).collect(Collectors.joining(",")),
          transition
      ));
      traverser = nextTraverser;
    }
    return traverser;
  }

  @Override
  public String toString() {
    return "TraversableState{" +
        "state=" + state +
        '}';
  }
}
