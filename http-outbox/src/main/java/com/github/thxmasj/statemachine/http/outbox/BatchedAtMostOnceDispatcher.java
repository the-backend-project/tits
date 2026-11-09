package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.BatchedRequest;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can't be rolled back, as part of batches. See [BatchedAtMostOnceWithRollbackDispatcher] for one which can be rolled back.
public final class BatchedAtMostOnceDispatcher<X, RQ> extends BaseDispatcher<X, BatchedRequest<RQ>> implements Dispatcher<X, BatchedRequest<RQ>> {

  private BatchedAtMostOnceDispatcher(AtMostOnce<RQ, Void> outbox, BiFunction<TransitionContext<?>, X, Mono<BatchedRequest<RQ>>> inputCreator) {
    super(outbox, outbox.batchedRequestDispatched(), inputCreator, Map.of(outbox, outbox.transitions()));
  }

  public static <X, RQ> BatchedAtMostOnceDispatcher<X, RQ> of(AtMostOnce<RQ, Void> outbox, Function<X, BatchedRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, RQ> BatchedAtMostOnceDispatcher<X, RQ> reactive(
      AtMostOnce<RQ, Void> outbox,
      BiFunction<TransitionContext<?>, X, Mono<BatchedRequest<RQ>>> inputCreator
  ) {
    requireNoRollbackModels(outbox);
    return new BatchedAtMostOnceDispatcher<>(outbox, inputCreator);
  }

}
