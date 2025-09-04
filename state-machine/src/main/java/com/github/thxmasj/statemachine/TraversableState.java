package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.TransitionModel.Builder.onEvent;

import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.OK;
import com.github.thxmasj.statemachine.message.http.UnprocessableEntity;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

public class TraversableState {
  private final State state;
  private final Map<TransitionModel<?, ?, ?>, TraversableState> backwardTransitions;
  private final Map<TransitionModel<?, ?, ?>, TraversableState> forwardTransitions;
  private static final Map<EntityModel, TraversableState> cache = new HashMap<>();

  public TraversableState(
      State state,
      Map<TransitionModel<?, ?, ?>, TraversableState> backwardTransitions,
      Map<TransitionModel<?, ?, ?>, TraversableState> forwardTransitions
  ) {
    this.state = state;
    this.backwardTransitions = backwardTransitions;
    this.forwardTransitions = forwardTransitions;
  }

  public State state() {
    return state;
  }

  public Set<TransitionModel<?, ?, ?>> forwardTransitions() {
    return forwardTransitions.keySet();
  }

  public static synchronized TraversableState create(EntityModel model) {
    return cache.computeIfAbsent(model, m -> create(null, null, m.transitions(), m.initialState(), new HashMap<>()));
  }

  private static TraversableState create(
      TraversableState fromState,
      TransitionModel<?, ?, ?> transitionIn,
      List<TransitionModel<?, ?, ?>> transitions,
      State initialState,
      Map<State, TraversableState> visitedStates
  ) {
    boolean initial = visitedStates.isEmpty();
    State state = transitionIn != null ? transitionIn.toState() : initialState;
    if (visitedStates.containsKey(state)) {
      visitedStates.get(state).backwardTransitions.put(transitionIn, fromState);
      return visitedStates.get(state);
    }
    Map<TransitionModel<?, ?, ?>, TraversableState> forwardTransitions = new HashMap<>();
    Map<TransitionModel<?, ?, ?>, TraversableState> backwardTransitions = new HashMap<>();
    TraversableState node = new TraversableState(state, backwardTransitions, forwardTransitions);
    if (transitionIn != null)
      backwardTransitions.put(transitionIn, fromState);
    visitedStates.put(state, node);
    for (var transition : transitions) {
      if (transition.fromState().equals(state))
        forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    if (initial && forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.UnknownEntity)) {
      var transition = onEvent(BuiltinEventTypes.UnknownEntity)
          .from(state)
          .to(state)
          .response("Unknown entity", new BadRequest());
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.InvalidRequest)) {
      var transition = invalidRequestTransition(state);
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.RejectedRequest)) {
      var transition = rejectedRequestTransition(state);
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    if (forwardTransitions.keySet().stream().noneMatch(t -> t.eventType() == BuiltinEventTypes.Status)) {
      var transition = statusTransition(state);
      forwardTransitions.put(transition, create(node, transition, transitions, initialState, visitedStates));
    }
    return node;
  }

  private static TransitionModel<?, ?, ?> statusTransition(State state) {
    return onEvent(BuiltinEventTypes.Status)
        .from(state)
        .to(state)
        .assemble((_, _) -> state.name())
        .response(new OK());
  }

  private static TransitionModel<?, ?, ?> invalidRequestTransition(State state) {
    return builtinTransition(state, BuiltinEventTypes.InvalidRequest, new BadRequest());
  }

  private static TransitionModel<?, ?, ?> rejectedRequestTransition(State state) {
    return builtinTransition(state, BuiltinEventTypes.RejectedRequest, new UnprocessableEntity());
  }

  private static TransitionModel<String, String, ?> builtinTransition(State state, EventType<String, ?> eventType, OutgoingResponseCreator<String> response) {
    return onEvent(eventType)
        .from(state)
        .to(state)
        .withData((input, _) -> Mono.just(input.data()))
        .response(response);
  }

  public Collection<TraversableState> targetStates() {
    return forwardTransitions.values();
  }

  public <I, O> TransitionModel<I, ?, O> transition(EventType<I, O> eventType) {
    for (var transition : forwardTransitions.keySet()) {
      if (transition.eventType() == eventType)
        return (TransitionModel<I, ?, O>)transition;
    }
    return null;
  }

  public TraversableState backward(EventType<?, ?> eventType) {
    return backwardTransitions.entrySet()
        .stream()
        .filter(e -> e.getKey().eventType() == eventType)
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  public TraversableState forward(EventType<?, ?> eventType) {
    return forward(List.of(eventType));
  }

  public TraversableState forward(List<? extends EventType<?, ?>> eventTypes) {
    var traverser = this;
    for (var eventType : eventTypes) {
//      if (eventType.isRollback()) {
//
//      }
      var transition = traverser.transition(eventType);
      if (transition == null) return null;
      var nextTraverser = traverser.forwardTransitions.get(transition);
      if (nextTraverser == null) throw new IllegalStateException(String.format(
          "Traversing from %s with %s: Missing traverser for valid transition (%s --%s--> %s)",
          state,
          eventTypes.stream().map(EventType::name).collect(Collectors.joining(",")),
          traverser.state(),
          transition.eventType(),
          transition.toState()
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
