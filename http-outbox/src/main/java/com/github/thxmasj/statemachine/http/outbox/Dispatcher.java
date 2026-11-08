package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// Pairs an outbox request with the creator of its input, which creates it from data of type `X`. The input is the
/// request message, or a [HttpOutboxRequest.BatchedRequest] for a batched outbox request. For an outbox request that
/// can be rolled back, `Y` is the type of the data the rollback is dispatched with. The creator is kept outside the
/// outbox request, so that it is reusable regardless of where its request messages come from.
///
/// The input type of the outbox request is checked when the dispatcher is created, and hidden from then on, so that a
/// process can dispatch requests to an outbox request without knowing it.
/// ```
/// .trigger(dispatcher.requestDispatched())
/// .withReactive((c, d) -> dispatcher.input(c, d))
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
public final class Dispatcher<X, Y> {

  private final HttpOutboxRequest<?, ?> outbox;
  private final EventType<Object, ?> requestDispatched;
  private final BiFunction<TransitionContext<?>, X, Mono<Object>> input;
  private final EventType<Y, Void> rollbackDispatched;

  private Dispatcher(
      HttpOutboxRequest<?, ?> outbox,
      EventType<Object, ?> requestDispatched,
      BiFunction<TransitionContext<?>, X, Mono<Object>> input,
      EventType<Y, Void> rollbackDispatched
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.input = input;
    this.rollbackDispatched = rollbackDispatched;
  }

  public static <X, I> Dispatcher<X, Void> of(HttpOutboxRequest<I, ?> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, I> Dispatcher<X, Void> reactive(
      HttpOutboxRequest<I, ?> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return new Dispatcher<>(outbox, hideInputType(outbox.requestDispatched()), hideInputType(inputCreator), null);
  }

  public static <X, Y, I> Dispatcher<X, Y> of(AtMostOnce<I, ?, Y, ?> outbox, Function<X, I> inputCreator) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)));
  }

  /// A dispatcher with a creator which creates the input reactively, in the context of the transition dispatching the
  /// request.
  public static <X, Y, I> Dispatcher<X, Y> reactive(
      AtMostOnce<I, ?, Y, ?> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return new Dispatcher<>(
        outbox,
        hideInputType(outbox.requestDispatched()),
        hideInputType(inputCreator),
        outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null ?
            outbox.rollbackDispatched() :
            null
    );
  }

  public HttpOutboxRequest<?, ?> outbox() {
    return outbox;
  }

  public EventType<Object, ?> requestDispatched() {
    return requestDispatched;
  }

  /// The input of the request dispatched event, created from the given data in the given context of the transition
  /// dispatching the request.
  public Mono<Object> input(TransitionContext<?> context, X data) {
    return input.apply(context, data);
  }

  public EventType<Y, Void> rollbackDispatched() {
    if (rollbackDispatched == null)
      throw new IllegalStateException(outbox.name() + " can't be rolled back");
    return rollbackDispatched;
  }

  private static <X, I> BiFunction<TransitionContext<?>, X, Mono<Object>> hideInputType(
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator
  ) {
    return (c, x) -> inputCreator.apply(c, x).map(i -> i);
  }

  // Safe as long as the event type only gets input created by the creator paired with the outbox request, which is
  // ensured by the factory methods.
  @SuppressWarnings("unchecked")
  private static <O> EventType<Object, O> hideInputType(EventType<?, O> eventType) {
    return (EventType<Object, O>) eventType;
  }

}
