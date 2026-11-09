package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import java.util.function.BiFunction;
import reactor.core.publisher.Mono;

/// An event type together with the creator of the data of its events, which creates the data from data of type `X`,
/// reactively and in the context of the triggering transition. Triggering an event creator only requires data of type
/// `X`, so the data type of the event type itself can be hidden from the triggering transition:
/// ```
/// .trigger(eventCreator).with(d -> x).on(entityModel).identifiedBy(...)
/// ```
public record EventCreator<X, I, O>(
    EventType<I, O> eventType,
    BiFunction<TransitionContext<?>, X, Mono<I>> dataCreator
) {}
