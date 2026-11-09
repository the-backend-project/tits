package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventCreator;
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

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request. If the outbox request can be rolled back, the
/// dispatcher also has the creator of the input of its rollback models, which creates it from the data the rollback is
/// dispatched with (of type `Y`) and the request being rolled back.
///
/// As the rollback input can only be created where the request being rolled back is, i.e. in the rollback transition
/// of the outbox request, the rollback message creator is part of the transitions of the outbox request. The
/// transitions to register for it are therefore the ones of its dispatcher, see [#transitions()].
public final class AtMostOnceDispatcher<X, Y> implements Dispatcher<X> {

  private final AtMostOnce<?, ?, ?> outbox;
  private final EventCreator<X, ?, ?> requestDispatched;
  private final EventType<Y, Void> rollbackDispatched;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;

  private <I> AtMostOnceDispatcher(
      AtMostOnce<I, ?, ?> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator,
      EventType<Y, Void> rollbackDispatched,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    this.outbox = outbox;
    this.requestDispatched = new EventCreator<>(outbox.requestDispatched(), inputCreator);
    this.rollbackDispatched = rollbackDispatched;
    this.transitions = transitions;
  }

  /// A dispatcher of requests which can't be rolled back.
  public static <X, I> AtMostOnceDispatcher<X, Void> of(AtMostOnce<I, ?, Void> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher of requests which can't be rolled back, with a creator which creates the input reactively, in the
  /// context of the transition dispatching the request.
  public static <X, I> AtMostOnceDispatcher<X, Void> reactive(
      AtMostOnce<I, ?, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return new AtMostOnceDispatcher<>(outbox, inputCreator, null, Map.of(outbox, outbox.transitions()));
  }

  /// A dispatcher of requests which can be rolled back, with data of type `Y`.
  public static <X, Y, I, RQ, RRI> AtMostOnceDispatcher<X, Y> of(
      AtMostOnce<I, RQ, RRI> outbox,
      Function<X, I> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)), rollbackMessageCreator);
  }

  /// A dispatcher of requests which can be rolled back, with data of type `Y`, and with a creator which creates the
  /// input reactively, in the context of the transition dispatching the request.
  public static <X, Y, I, RQ, RRI> AtMostOnceDispatcher<X, Y> reactive(
      AtMostOnce<I, RQ, RRI> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    if (outbox.rollbackOnAcceptedModel() == null && outbox.rollbackOnUnknownModel() == null)
      throw new IllegalArgumentException(outbox.name() + " has no rollback models");
    var transitions = new LinkedHashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.put(outbox, outbox.transitions(rollbackMessageCreator));
    if (outbox.rollbackOnAcceptedModel() != null)
      transitions.put(outbox.rollbackOnAcceptedModel(), outbox.rollbackOnAcceptedModel().transitions());
    if (outbox.rollbackOnUnknownModel() != null)
      transitions.put(outbox.rollbackOnUnknownModel(), outbox.rollbackOnUnknownModel().transitions());
    return new AtMostOnceDispatcher<>(outbox, inputCreator, outbox.rollbackDispatched(), transitions);
  }

  @Override
  public AtMostOnce<?, ?, ?> outbox() {
    return outbox;
  }

  @Override
  public EventCreator<X, ?, ?> requestDispatched() {
    return requestDispatched;
  }

  /// Rolls the request back with data of type `Y`.
  public EventType<Y, Void> rollbackDispatched() {
    if (rollbackDispatched == null)
      throw new IllegalStateException(outbox.name() + " can't be rolled back");
    return rollbackDispatched;
  }

  @Override
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return transitions;
  }

}
