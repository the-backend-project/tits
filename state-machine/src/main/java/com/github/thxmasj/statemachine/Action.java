package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface Action<OUTPUT_TYPE> extends DataRequirer {

  Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input input
  );

  interface Context<OUTPUT_TYPE> {

    Event output(EventType eventType, OUTPUT_TYPE data);

  }

}
