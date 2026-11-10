package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can be rolled back, with data of type `Y`, and whose requests are dispatched as part of batches.
///
/// Besides the creator of the input, it has the creator of the rollback request messages, which creates them from the
/// data the rollback is dispatched with (of type `Y`) and the request being rolled back. The rollback requests are
/// dispatched to the rollback models with their request messages, or as part of batches with a [BatchedRequest].
///
/// As the rollback request message can only be created where the request being rolled back is, i.e. in the rollback
/// transition of the outbox request, the rollback message creator is part of the transitions of the outbox request.
/// The transitions to register for it are therefore the ones of its dispatcher, see [#transitions()].
public final class BatchedAtMostOnceWithRollbackDispatcher<X, RQ, Y> extends BaseDispatcher<X, BatchedRequest<RQ>>
    implements DispatcherWithRollback<X, BatchedRequest<RQ>, Y> {

  private final AtMostOnce<RQ, ?> outbox;

  private <RRQ> BatchedAtMostOnceWithRollbackDispatcher(
      AtMostOnce<RQ, RRQ> outbox,
      BiFunction<DispatchContext, X, Mono<BatchedRequest<RQ>>> inputCreator,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    super(outbox, outbox.batchedRequestDispatched(), inputCreator, transitions);
    this.outbox = outbox;
  }

  public static <X, RQ, Y, RRQ> BatchedAtMostOnceWithRollbackDispatcher<X, RQ, Y> of(
      AtMostOnce<RQ, RRQ> outbox,
      Function<X, BatchedRequest<RQ>> inputCreator,
      BiFunction<DispatchContext, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<TypedHttpRequest<RRQ>>> rollbackMessageCreator
  ) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator), rollbackMessageCreator);
  }

  /// A dispatcher with a creator which creates the input reactively, from the data and the context of the transition
  /// dispatching the request.
  public static <X, RQ, Y, RRQ> BatchedAtMostOnceWithRollbackDispatcher<X, RQ, Y> reactive(
      AtMostOnce<RQ, RRQ> outbox,
      BiFunction<DispatchContext, X, Mono<BatchedRequest<RQ>>> inputCreator,
      BiFunction<DispatchContext, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<TypedHttpRequest<RRQ>>> rollbackMessageCreator
  ) {
    requireRollbackModels(outbox);
    return new BatchedAtMostOnceWithRollbackDispatcher<>(outbox, inputCreator, withRollbackModels(outbox, outbox.<Y>transitions(rollbackMessageCreator)));
  }

  /// A dispatcher with the rollback requests dispatched as part of batches.
  public static <X, RQ, Y, RRQ> BatchedAtMostOnceWithRollbackDispatcher<X, RQ, Y> ofBatchedRollback(
      AtMostOnce<RQ, RRQ> outbox,
      Function<X, BatchedRequest<RQ>> inputCreator,
      BiFunction<DispatchContext, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<BatchedRequest<RRQ>>> rollbackMessageCreator
  ) {
    return reactiveBatchedRollback(outbox, BaseDispatcher.reactive(inputCreator), rollbackMessageCreator);
  }

  /// A dispatcher with the rollback requests dispatched as part of batches, and with a creator which creates the input
  /// reactively, from the data and the context of the transition dispatching the request.
  public static <X, RQ, Y, RRQ> BatchedAtMostOnceWithRollbackDispatcher<X, RQ, Y> reactiveBatchedRollback(
      AtMostOnce<RQ, RRQ> outbox,
      BiFunction<DispatchContext, X, Mono<BatchedRequest<RQ>>> inputCreator,
      BiFunction<DispatchContext, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<BatchedRequest<RRQ>>> rollbackMessageCreator
  ) {
    requireRollbackModels(outbox);
    return new BatchedAtMostOnceWithRollbackDispatcher<>(outbox, inputCreator, withRollbackModels(outbox, outbox.<Y>batchedRollbackTransitions(rollbackMessageCreator)));
  }

  @Override
  public EventType<Y, Void> rollbackDispatched() {
    return outbox.rollbackDispatched();
  }

}
