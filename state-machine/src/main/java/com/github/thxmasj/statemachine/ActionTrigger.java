package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public record ActionTrigger<T>(Action<T> action, T data, EventLog eventLog) {

  public Mono<InputEvent<?>> trigger() {
    return action.execute(data);
  }

  public ActionTrigger<T> withEvent(Event<?> event) {
    return new ActionTrigger<>(action, data, eventLog.withNewEvent(event));
  }

}
