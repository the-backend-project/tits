package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface IncomingResponseValidator<OUTPUT_TYPE> extends DataRequirer {

  Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingResponse response,
      Input input
  );

  interface Context<OUTPUT_TYPE> {

    Event requestUndelivered(String cause);

    Event validResponse(EventType eventType, OUTPUT_TYPE data);

    Event invalidResponse(String cause);

    Event rollback(String cause);

  }

}
