package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventCreator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;

/// Pairs an outbox request with the creator of its input, which creates it from data of type `X`, so that the outbox
/// request is reusable regardless of where its request messages come from. The input is the request message, or a
/// [HttpOutboxRequest.BatchedRequest] for a batched outbox request.
///
/// The input type of the outbox request is hidden by [#requestDispatched()], so that a process can dispatch requests to
/// an outbox request knowing only the type of the data the input is created from:
/// ```
/// .trigger(dispatcher.requestDispatched())
/// .with(d -> x)
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
public sealed interface Dispatcher<X> permits AtLeastOnceDispatcher, AtMostOnceDispatcher, AtMostOnceWithRollbackDispatcher {

  HttpOutboxRequest<?, ?> outbox();

  /// The request dispatched event of the outbox request, with its input created from data of type `X`.
  EventCreator<X, ?, ?> requestDispatched();

  /// The transitions to register for the outbox request, including those of its rollback models, if any.
  Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions();

}
