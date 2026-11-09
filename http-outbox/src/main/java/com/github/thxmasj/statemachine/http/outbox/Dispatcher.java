package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/// Pairs an outbox request with the creator of its input (of type `I`), which creates it from data of type `X`, so
/// that the outbox request is reusable regardless of where its request messages come from. The input is the request
/// message, or, for a batched dispatcher, a [HttpOutboxRequest.BatchedRequest] with the request message and the id of
/// the batch which the request belongs to.
/// ```
/// .assemble(c -> tuple(..., DispatchContext.of(c)))
/// .trigger(dispatcher.requestDispatched())
/// .withReactive(d -> dispatcher.input(d.tN(), x))
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
public sealed interface Dispatcher<X, I> permits AtLeastOnceDispatcher, BatchedAtLeastOnceDispatcher,
    AtMostOnceDispatcher, BatchedAtMostOnceDispatcher, DispatcherWithRollback {

  HttpOutboxRequest<?> outbox();

  EventType<I, ?> requestDispatched();

  /// The input of the request dispatched event, created from the given data and context of the dispatching transition,
  /// both assembled in its assemble step.
  Mono<I> input(DispatchContext context, X data);

  /// The transitions to register for the outbox request, including those of its rollback models, if any.
  Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions();

}
