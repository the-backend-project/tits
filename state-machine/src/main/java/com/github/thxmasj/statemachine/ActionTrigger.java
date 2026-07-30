package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public record ActionTrigger<T, U>(Action<T, U> action, T data, EventLog eventLog) {

  public Mono<InputEvent<U>> trigger() {
    return action.execute(data);
  }

  public ActionTrigger<T, U> withEvent(Event<?> event) {
    return new ActionTrigger<>(action, data, eventLog.withNewEvent(event));
  }

}
