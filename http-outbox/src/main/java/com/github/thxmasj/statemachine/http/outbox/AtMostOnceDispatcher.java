package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can't be rolled back. See [AtMostOnceWithRollbackDispatcher] for one which can.
public final class AtMostOnceDispatcher<X, RQ> extends BaseDispatcher<X, TypedHttpRequest<RQ>> implements Dispatcher<X, TypedHttpRequest<RQ>> {

  private AtMostOnceDispatcher(AtMostOnce<RQ, Void> outbox, BiFunction<TransitionContext<?>, X, Mono<TypedHttpRequest<RQ>>> inputCreator) {
    super(outbox, outbox.requestDispatched(), inputCreator, Map.of(outbox, outbox.transitions()));
  }

  public static <X, RQ> AtMostOnceDispatcher<X, RQ> of(AtMostOnce<RQ, Void> outbox, Function<X, TypedHttpRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, RQ> AtMostOnceDispatcher<X, RQ> reactive(
      AtMostOnce<RQ, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<TypedHttpRequest<RQ>>> inputCreator
  ) {
    requireNoRollbackModels(outbox);
    return new AtMostOnceDispatcher<>(outbox, inputCreator);
  }

}
