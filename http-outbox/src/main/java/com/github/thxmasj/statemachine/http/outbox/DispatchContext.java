package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import java.time.ZonedDateTime;
import java.util.UUID;

/// The context which a request is dispatched in, for the creators of request messages which depend on it. It is
/// assembled in the assemble step of the transition dispatching the request, see [#of(TransitionContext)].
///
/// @param processId the id of the process dispatching the request
/// @param correlationId the correlation id of the transition dispatching the request
/// @param timestamp the timestamp of the transition dispatching the request
public record DispatchContext(UUID processId, String correlationId, ZonedDateTime timestamp) {

  /// The context of a request dispatched by the transition of the given context.
  public static DispatchContext of(TransitionContext<?> context) {
    return new DispatchContext(context.log().entityId().value(), context.correlationId(), context.timestamp());
  }

}
