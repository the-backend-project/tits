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

/// A [Dispatcher] of requests to an [AtLeastOnce] outbox request.
public final class AtLeastOnceDispatcher<X, I> implements Dispatcher<X, I> {

  private final AtLeastOnce<I, ?> outbox;
  private final BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator;

  private AtLeastOnceDispatcher(AtLeastOnce<I, ?> outbox, BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator) {
    this.outbox = outbox;
    this.inputCreator = inputCreator;
  }

  public static <X, I> AtLeastOnceDispatcher<X, I> of(AtLeastOnce<I, ?> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, I> AtLeastOnceDispatcher<X, I> reactive(
      AtLeastOnce<I, ?> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return new AtLeastOnceDispatcher<>(outbox, inputCreator);
  }

  @Override
  public AtLeastOnce<I, ?> outbox() {
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
