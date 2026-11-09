package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// The state common to the [Dispatcher] implementations.
abstract class BaseDispatcher<X, I> {

  private final HttpOutboxRequest<?> outbox;
  private final EventType<I, ?> requestDispatched;
  private final BiFunction<DispatchContext, X, Mono<I>> inputCreator;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;

  BaseDispatcher(
      HttpOutboxRequest<?> outbox,
      EventType<I, ?> requestDispatched,
      BiFunction<DispatchContext, X, Mono<I>> inputCreator,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.inputCreator = inputCreator;
    this.transitions = transitions;
  }

  public HttpOutboxRequest<?> outbox() {
    return outbox;
  }

  public EventType<I, ?> requestDispatched() {
    return requestDispatched;
  }

  public Mono<I> input(DispatchContext context, X data) {
    return inputCreator.apply(context, data);
  }

  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return transitions;
  }

  static <X, I> BiFunction<DispatchContext, X, Mono<I>> reactive(Function<X, I> inputCreator) {
    return (_, x) -> Mono.just(inputCreator.apply(x));
  }

  static void requireNoRollbackModels(AtMostOnce<?, ?> outbox) {
    if (outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null)
      throw new IllegalArgumentException(outbox.name() + " has rollback models, use a dispatcher with rollback");
  }

  static void requireRollbackModels(AtMostOnce<?, ?> outbox) {
    if (outbox.rollbackOnAcceptedModel() == null && outbox.rollbackOnUnknownModel() == null)
      throw new IllegalArgumentException(outbox.name() + " has no rollback models, use a dispatcher without rollback");
  }

  static <RRQ> Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> withRollbackModels(
      AtMostOnce<?, RRQ> outbox,
      Map<State, List<TransitionModel<?, ?>>> outboxTransitions
  ) {
    var transitions = new LinkedHashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.put(outbox, outboxTransitions);
    if (outbox.rollbackOnAcceptedModel() != null)
      transitions.put(outbox.rollbackOnAcceptedModel(), outbox.rollbackOnAcceptedModel().transitions());
    if (outbox.rollbackOnUnknownModel() != null)
      transitions.put(outbox.rollbackOnUnknownModel(), outbox.rollbackOnUnknownModel().transitions());
    return transitions;
  }

}
