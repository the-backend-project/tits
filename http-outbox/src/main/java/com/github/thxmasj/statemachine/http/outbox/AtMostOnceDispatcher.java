package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
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
public final class AtMostOnceDispatcher<X, I> implements Dispatcher<X, I> {

  private final AtMostOnce<I, ?, Void> outbox;
  private final BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator;

  private AtMostOnceDispatcher(AtMostOnce<I, ?, Void> outbox, BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator) {
    if (outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null)
      throw new IllegalArgumentException(outbox.name() + " has rollback models, use AtMostOnceWithRollbackDispatcher");
    this.outbox = outbox;
    this.inputCreator = inputCreator;
  }

  public static <X, I> AtMostOnceDispatcher<X, I> of(AtMostOnce<I, ?, Void> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, I> AtMostOnceDispatcher<X, I> reactive(
      AtMostOnce<I, ?, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return new AtMostOnceDispatcher<>(outbox, inputCreator);
  }

  @Override
  public AtMostOnce<I, ?, Void> outbox() {
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

  @Override
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(outbox, outbox.transitions());
  }

}
