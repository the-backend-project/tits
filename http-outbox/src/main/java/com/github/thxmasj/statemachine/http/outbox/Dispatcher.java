package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// Pairs an outbox request with the creators of its input, so that the outbox request is reusable regardless of where
/// its request messages come from:
///
/// - the creator of the input, from data of type `X`. The input is the request message, or a
///   [HttpOutboxRequest.BatchedRequest] for a batched outbox request.
/// - for an [AtMostOnce] which can be rolled back, the creator of the input of its rollback models, from data of type
///   `Y` and the request being rolled back.
///
/// The input types of the outbox request are checked when the dispatcher is created, and hidden from then on, so that
/// a process can dispatch requests to an outbox request without knowing them.
/// ```
/// .trigger(dispatcher.requestDispatched())
/// .withReactive((c, d) -> dispatcher.input(c, d))
/// .on(dispatcher.outbox())
/// .identifiedBy(newEntityId())
/// ```
/// As the rollback message creator is part of the transitions of the outbox request, the transitions to register for
/// it are the ones of its dispatcher, see [#transitions()].
public final class Dispatcher<X, Y> {

  private final HttpOutboxRequest<?, ?> outbox;
  private final EventType<Object, ?> requestDispatched;
  private final BiFunction<TransitionContext<?>, X, Mono<Object>> input;
  private final EventType<Y, Void> rollbackDispatched;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;

  private Dispatcher(
      HttpOutboxRequest<?, ?> outbox,
      EventType<Object, ?> requestDispatched,
      BiFunction<TransitionContext<?>, X, Mono<Object>> input,
      EventType<Y, Void> rollbackDispatched,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.input = input;
    this.rollbackDispatched = rollbackDispatched;
    this.transitions = transitions;
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
    return new Dispatcher<>(
        outbox,
        hideInputType(outbox.requestDispatched()),
        hideInputType(inputCreator),
        null,
        Map.of(outbox, outbox.transitions())
    );
  }

  /// A dispatcher of requests which can be rolled back, with data of type `Y`.
  public static <X, Y, I, RQ, RRI> Dispatcher<X, Y> of(
      AtMostOnce<I, RQ, RRI> outbox,
      Function<X, I> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    return reactive(outbox, (_, x) -> Mono.just(inputCreator.apply(x)), rollbackMessageCreator);
  }

  /// A dispatcher of requests which can be rolled back, with data of type `Y`, and with a creator which creates the
  /// input reactively, in the context of the transition dispatching the request.
  public static <X, Y, I, RQ, RRI> Dispatcher<X, Y> reactive(
      AtMostOnce<I, RQ, RRI> outbox,
      BiFunction<TransitionContext<?>, X, Mono<I>> inputCreator,
      BiFunction<TransitionContext<?>, Tuple2<Y, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
  ) {
    var transitions = new LinkedHashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.put(outbox, outbox.transitions(rollbackMessageCreator));
    if (outbox.rollbackOnAcceptedModel() != null)
      transitions.put(outbox.rollbackOnAcceptedModel(), outbox.rollbackOnAcceptedModel().transitions());
    if (outbox.rollbackOnUnknownModel() != null)
      transitions.put(outbox.rollbackOnUnknownModel(), outbox.rollbackOnUnknownModel().transitions());
    return new Dispatcher<>(
        outbox,
        hideInputType(outbox.requestDispatched()),
        hideInputType(inputCreator),
        outbox.rollbackDispatched(),
        transitions
    );
  }

  public HttpOutboxRequest<?, ?> outbox() {
    return outbox;
  }

  /// The transitions to register for the outbox request, and for its rollback models if it can be rolled back.
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return transitions;
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
