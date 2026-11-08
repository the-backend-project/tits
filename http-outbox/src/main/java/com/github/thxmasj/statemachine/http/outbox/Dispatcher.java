package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.Input;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.UUID;
import java.util.function.Function;

/// Pairs an outbox request with the creator of its request messages, which creates them from data of type `X`, and, for
/// an outbox request that can be rolled back, with the creator of its rollback request messages, which creates them
/// from data of type `Y`. The message creators are kept outside the outbox request, so that it is reusable regardless
/// of where its request messages come from.
///
/// The payload types of the request messages are checked when the dispatcher is created, and hidden from then on, so
/// that a process can dispatch requests to an outbox request without knowing them.
/// ```
/// .trigger(dispatcher.requestDispatched())
/// .with(d -> dispatcher.input(d))
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
public final class Dispatcher<X, Y> {

  private final HttpOutboxRequest<?> outbox;
  private final EventType<Input<?>, ?> requestDispatched;
  private final Function<X, Input<?>> input;
  private final EventType<Input<?>, Void> rollbackDispatched;
  private final Function<Y, Input<?>> rollbackInput;

  private Dispatcher(
      HttpOutboxRequest<?> outbox,
      EventType<Input<?>, ?> requestDispatched,
      Function<X, Input<?>> input,
      EventType<Input<?>, Void> rollbackDispatched,
      Function<Y, Input<?>> rollbackInput
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.input = input;
    this.rollbackDispatched = rollbackDispatched;
    this.rollbackInput = rollbackInput;
  }

  public static <X, RQ> Dispatcher<X, Void> of(
      HttpOutboxRequest<RQ> outbox,
      Function<X, TypedHttpRequest<RQ>> messageCreator
  ) {
    return new Dispatcher<>(
        outbox,
        hidePayloadType(outbox.requestDispatched()),
        x -> Input.of(messageCreator.apply(x)),
        null,
        null
    );
  }

  public static <X, Y, RQ, RRQ> Dispatcher<X, Y> reversible(
      AtMostOnce<RQ, RRQ> outbox,
      Function<X, TypedHttpRequest<RQ>> messageCreator,
      Function<Y, TypedHttpRequest<RRQ>> rollbackMessageCreator
  ) {
    return new Dispatcher<>(
        outbox,
        hidePayloadType(outbox.requestDispatched()),
        x -> Input.of(messageCreator.apply(x)),
        hidePayloadType(outbox.rollbackDispatched()),
        y -> Input.of(rollbackMessageCreator.apply(y))
    );
  }

  /// A dispatcher of requests to a batched outbox request, with the batch id of a request extracted from the data of
  /// its request message.
  public Dispatcher<X, Y> batched(Function<X, UUID> batchIdExtractor) {
    return new Dispatcher<>(
        outbox,
        requestDispatched,
        x -> new Input<>(input.apply(x).request(), batchIdExtractor.apply(x)),
        rollbackDispatched,
        rollbackInput
    );
  }

  public HttpOutboxRequest<?> outbox() {
    return outbox;
  }

  public EventType<Input<?>, ?> requestDispatched() {
    return requestDispatched;
  }

  /// The input of the request dispatched event, with the request message created from the given data.
  public Input<?> input(X data) {
    return input.apply(data);
  }

  public EventType<Input<?>, Void> rollbackDispatched() {
    if (rollbackDispatched == null)
      throw new IllegalStateException(outbox.name() + " can't be rolled back");
    return rollbackDispatched;
  }

  /// The input of the rollback dispatched event, with the rollback request message created from the given data.
  public Input<?> rollbackInput(Y data) {
    return rollbackInput.apply(data);
  }

  // Safe as long as the event type only gets input created by the message creator paired with the outbox request,
  // which is ensured by the factory methods.
  @SuppressWarnings("unchecked")
  private static <O> EventType<Input<?>, O> hidePayloadType(EventType<? extends Input<?>, O> eventType) {
    return (EventType<Input<?>, O>) eventType;
  }

}
