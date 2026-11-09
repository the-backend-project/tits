package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import java.util.UUID;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/// An event type as seen by the transition triggering it, which gives data of type `X` instead of the input of the
/// event type itself. The input is created from the data by the data creator, reactively and in the context of the
/// triggering transition, when the trigger is applied. The event triggered is of the underlying event type, so the type
/// of its input can be hidden from the triggering transition:
/// ```
/// .trigger(eventCreator).with(d -> x).on(entityModel).identifiedBy(...)
/// ```
public record EventCreator<X, I, O>(
    EventType<I, O> eventType,
    BiFunction<TransitionContext<?>, X, Mono<I>> dataCreator
) implements EventType<X, O> {

  @Override
  public String name() {
    return eventType.name();
  }

  @Override
  public UUID id() {
    return eventType.id();
  }

  /// Unknown, as the data is never stored as such, only the input created from it.
  @Override
  public DataType<X> inputDataType() {
    return DataType.unknown();
  }

  @Override
  public DataType<O> outputDataType() {
    return eventType.outputDataType();
  }

}
