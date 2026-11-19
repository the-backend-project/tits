package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchedRequest;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtLeastOnce] outbox request, as part of batches.
public final class BatchedAtLeastOnceDispatcher<X, RQ> extends BaseDispatcher<X, BatchedRequest<RQ>> implements Dispatcher<X, BatchedRequest<RQ>> {

  private final AtLeastOnce<RQ> atLeastOnce;

  private BatchedAtLeastOnceDispatcher(AtLeastOnce<RQ> outbox, BiFunction<DispatchContext, X, Mono<BatchedRequest<RQ>>> inputCreator) {
    super(outbox, outbox.batchedRequestDispatched(), inputCreator, List.of(outbox));
    this.atLeastOnce = outbox;
  }

  public static <X, RQ> BatchedAtLeastOnceDispatcher<X, RQ> of(AtLeastOnce<RQ> outbox, Function<X, BatchedRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, from the data and the context of the transition
  /// dispatching the request.
  public static <X, RQ> BatchedAtLeastOnceDispatcher<X, RQ> reactive(
      AtLeastOnce<RQ> outbox,
      BiFunction<DispatchContext, X, Mono<BatchedRequest<RQ>>> inputCreator
  ) {
    return new BatchedAtLeastOnceDispatcher<>(outbox, inputCreator);
  }

  /// Sends an accepted or dead request again, as a retry would, on the entity selected by
  /// [HttpOutboxRequest#dispatchedBy]. See [AtLeastOnce#requestRedispatched()].
  public EventType<Void, TypedHttpRequest<RQ>> requestRedispatched() {
    return atLeastOnce.requestRedispatched();
  }

}
