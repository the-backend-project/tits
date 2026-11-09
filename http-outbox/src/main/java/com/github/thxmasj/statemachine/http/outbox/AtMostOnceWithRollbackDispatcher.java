package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can be rolled back, with data of type `Y`. Besides
/// the creator of the input, it has the creator of the input of the rollback models, which creates it from the data
/// the rollback is dispatched with and the request being rolled back.
///
/// As the rollback input can only be created where the request being rolled back is, i.e. in the rollback transition
/// of the outbox request, the rollback message creator is part of the transitions of the outbox request. The
/// transitions to register for it are therefore the ones of its dispatcher, see [#transitions()].
public final class AtMostOnceWithRollbackDispatcher<X, I, Y> implements Dispatcher<X, I> {

  private final AtMostOnce<I, ?, ?> outbox;
  private final BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;

  private <RQ, RRI> AtMostOnceWithRollbackDispatcher(
      AtMostOnce<I, RQ, RRI> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    if (outbox.rollbackOnAcceptedModel() == null && outbox.rollbackOnUnknownModel() == null)
      throw new IllegalArgumentException(outbox.name() + " has no rollback models, use AtMostOnceDispatcher");
    this.outbox = outbox;
    this.inputCreator = inputCreator;
    var transitions = new LinkedHashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.put(outbox, outbox.transitions(rollbackMessageCreator));
    if (outbox.rollbackOnAcceptedModel() != null)
      transitions.put(outbox.rollbackOnAcceptedModel(), outbox.rollbackOnAcceptedModel().transitions());
    if (outbox.rollbackOnUnknownModel() != null)
      transitions.put(outbox.rollbackOnUnknownModel(), outbox.rollbackOnUnknownModel().transitions());
    this.transitions = transitions;
  }

  public static <X, I, Y, RQ, RRI> AtMostOnceWithRollbackDispatcher<X, I, Y> of(
      AtMostOnce<I, RQ, RRI> outbox,
      Function<X, I> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)), rollbackMessageCreator);
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, I, Y, RQ, RRI> AtMostOnceWithRollbackDispatcher<X, I, Y> reactive(
      AtMostOnce<I, RQ, RRI> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return new AtMostOnceWithRollbackDispatcher<>(outbox, inputCreator, rollbackMessageCreator);
  }

  @Override
  public AtMostOnce<I, ?, ?> outbox() {
    return outbox;
  }

  @Override
  public EventType<I, ?> requestDispatched() {
    return outbox.requestDispatched();
  }

  @Override
  public Mono<I> input(TransitionContext<?> context, X data) {
    return inputCreator.apply(context, data);
  }

  /// Rolls the request back with data of type `Y`.
  public EventType<Y, Void> rollbackDispatched() {
    return outbox.rollbackDispatched();
  }

  @Override
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return transitions;
  }

}
