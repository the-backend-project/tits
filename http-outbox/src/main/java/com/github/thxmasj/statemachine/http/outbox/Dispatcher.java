package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.Input;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// Pairs an outbox request with the creator of its request messages, which creates them from data of type `X`. For an
/// outbox request that can be rolled back, `Y` is the type of the data the rollback is dispatched with. The message
/// creator is kept outside the outbox request, so that it is reusable regardless of where its request messages come
/// from.
///
/// The payload type of the request messages is checked when the dispatcher is created, and hidden from then on, so
/// that a process can dispatch requests to an outbox request without knowing it.
/// ```
/// .trigger(dispatcher.requestDispatched())
/// .withReactive((c, d) -> dispatcher.input(c, d))
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
public final class Dispatcher<X, Y> {

  private final HttpOutboxRequest<?> outbox;
  private final EventType<Input<?>, ?> requestDispatched;
  private final BiFunction<TransitionContext<?>, X, Mono<Input<?>>> input;
  private final EventType<Y, Void> rollbackDispatched;

  private Dispatcher(
      HttpOutboxRequest<?> outbox,
      EventType<Input<?>, ?> requestDispatched,
      BiFunction<TransitionContext<?>, X, Mono<Input<?>>> input,
      EventType<Y, Void> rollbackDispatched
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.input = input;
    this.rollbackDispatched = rollbackDispatched;
  }

  public static <X, RQ> Dispatcher<X, Void> of(
      HttpOutboxRequest<RQ> outbox,
      Function<X, TypedHttpRequest<RQ>> messageCreator
  ) {
    return reactive(outbox, (_, x) -> Mono.just(messageCreator.apply(x)));
  }

  /// A dispatcher with a message creator which creates the request messages reactively, in the context of the
  /// transition dispatching the request.
  public static <X, RQ> Dispatcher<X, Void> reactive(
      HttpOutboxRequest<RQ> outbox,
      BiFunction<TransitionContext<?>, X, Mono<TypedHttpRequest<RQ>>> messageCreator
  ) {
    return new Dispatcher<>(outbox, hidePayloadType(outbox.requestDispatched()), input(messageCreator), null);
  }

  public static <X, Y, RQ> Dispatcher<X, Y> of(
      AtMostOnce<RQ, Y, ?> outbox,
      Function<X, TypedHttpRequest<RQ>> messageCreator
  ) {
    return reactive(outbox, (_, x) -> Mono.just(messageCreator.apply(x)));
  }

  /// A dispatcher with a message creator which creates the request messages reactively, in the context of the
  /// transition dispatching the request.
  public static <X, Y, RQ> Dispatcher<X, Y> reactive(
      AtMostOnce<RQ, Y, ?> outbox,
      BiFunction<TransitionContext<?>, X, Mono<TypedHttpRequest<RQ>>> messageCreator
  ) {
    return new Dispatcher<>(
        outbox,
        hidePayloadType(outbox.requestDispatched()),
        input(messageCreator),
        outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null ?
            outbox.rollbackDispatched() :
            null
    );
  }

  /// A dispatcher of requests to a batched outbox request, with the batch id of a request extracted from the data of
  /// its request message.
  public Dispatcher<X, Y> batched(Function<X, UUID> batchIdExtractor) {
    return new Dispatcher<>(
        outbox,
        requestDispatched,
        (c, x) -> input.apply(c, x).map(i -> new Input<>(i.request(), batchIdExtractor.apply(x))),
        rollbackDispatched
    );
  }

  public HttpOutboxRequest<?> outbox() {
    return outbox;
  }

  public EventType<Input<?>, ?> requestDispatched() {
    return requestDispatched;
  }

  /// The input of the request dispatched event, with the request message created from the given data in the given
  /// context of the transition dispatching the request.
  public Mono<Input<?>> input(TransitionContext<?> context, X data) {
    return input.apply(context, data);
  }

  public EventType<Y, Void> rollbackDispatched() {
    if (rollbackDispatched == null)
      throw new IllegalStateException(outbox.name() + " can't be rolled back");
    return rollbackDispatched;
  }

  private static <X, RQ> BiFunction<TransitionContext<?>, X, Mono<Input<?>>> input(
      BiFunction<TransitionContext<?>, X, Mono<TypedHttpRequest<RQ>>> messageCreator
  ) {
    return (c, x) -> messageCreator.apply(c, x).map(Input::of);
  }

  // Safe as long as the event type only gets input created by the message creator paired with the outbox request,
  // which is ensured by the factory methods.
  @SuppressWarnings("unchecked")
  private static <O> EventType<Input<?>, O> hidePayloadType(EventType<? extends Input<?>, O> eventType) {
    return (EventType<Input<?>, O>) eventType;
  }

}
