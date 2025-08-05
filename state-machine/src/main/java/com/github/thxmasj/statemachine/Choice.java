package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Choice<I, T> {

  Mono<InputEvent<I>> execute(
      T data,
      InputEvent<I> event,
      EntityId entityId,
      Context<I> context,
      Input input
  );

  interface Context<OUTPUT_TYPE> {

    InputEvent<OUTPUT_TYPE> output(EventType eventType, OUTPUT_TYPE data);

  }

}
