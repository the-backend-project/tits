package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Action<OUTPUT_TYPE> extends DataRequirer {

  Mono<InputEvent<OUTPUT_TYPE>> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input input
  );

  interface Context<OUTPUT_TYPE> {

    InputEvent<OUTPUT_TYPE> output(EventType eventType, OUTPUT_TYPE data);

  }

}
