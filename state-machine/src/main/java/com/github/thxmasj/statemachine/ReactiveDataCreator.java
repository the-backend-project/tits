package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface ReactiveDataCreator<I, T, P> {

  Mono<P> execute(InputEvent<I> inputEvent, T triggerData, EventLog eventLog);

  static <I, T> ReactiveDataCreator<I, T, I> fromInput(Class<I> unused) {
    return (inputEvent, _, _) -> Mono.just(inputEvent.data());
  }

  static <I, T, P> ReactiveDataCreator<I, T, P> fromEvent(EventType<?, P> eventType) {
    return (_, _, eventLog) -> Mono.just(eventLog.one(eventType));
  }

}
