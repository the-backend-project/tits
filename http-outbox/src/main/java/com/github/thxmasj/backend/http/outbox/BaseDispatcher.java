package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.EventType;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// The state common to the [Dispatcher] implementations, of an outbox request of type `R`.
abstract class BaseDispatcher<X, I, R extends HttpOutboxRequest<?>> {

  private final R outbox;
  private final EventType<I, ?> requestDispatched;
  private final BiFunction<DispatchContext, X, Mono<I>> inputCreator;

  BaseDispatcher(
      R outbox,
      EventType<I, ?> requestDispatched,
      BiFunction<DispatchContext, X, Mono<I>> inputCreator
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.inputCreator = inputCreator;
  }

  public R outbox() {
    return outbox;
  }

  public EventType<I, ?> requestDispatched() {
    return requestDispatched;
  }

  public Mono<I> input(DispatchContext context, X data) {
    return inputCreator.apply(context, data);
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


}
