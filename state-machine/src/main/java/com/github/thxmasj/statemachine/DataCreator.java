package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface DataCreator<I, O> extends DataRequirer {

  Mono<O> execute(InputEvent<I> inputEvent, EventLog eventLog);

  static <T> DataCreator<T, T> fromInput(Class<T> unused) {
    return (inputEvent, _) -> Mono.just(inputEvent.data());
  }

  static <I, O> DataCreator<I, O> fromEvent(EventType<?, O> eventType) {
    return (_, eventLog) -> Mono.just(eventLog.one(eventType));
  }

}
