package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventCreator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can't be rolled back. See
/// [AtMostOnceWithRollbackDispatcher] for one which can.
public final class AtMostOnceDispatcher<X> implements Dispatcher<X> {

  private final AtMostOnce<?, ?, Void> outbox;
  private final EventCreator<X, ?, ?> requestDispatched;

  private <I> AtMostOnceDispatcher(
      AtMostOnce<I, ?, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    this.outbox = outbox;
    this.requestDispatched = new EventCreator<>(outbox.requestDispatched(), inputCreator);
  }

  public static <X, I> AtMostOnceDispatcher<X> of(AtMostOnce<I, ?, Void> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, I> AtMostOnceDispatcher<X> reactive(
      AtMostOnce<I, ?, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    if (outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null)
      throw new IllegalArgumentException(outbox.name() + " has rollback models, use AtMostOnceWithRollbackDispatcher");
    return new AtMostOnceDispatcher<>(outbox, inputCreator);
  }

  @Override
  public AtMostOnce<?, ?, Void> outbox() {
    return outbox;
  }

  @Override
  public EventCreator<X, ?, ?> requestDispatched() {
    return requestDispatched;
  }

  @Override
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(outbox, outbox.transitions());
  }

}
