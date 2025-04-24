package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface IncomingRequestValidator<OUTPUT_TYPE> extends DataRequirer {

  default Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingRequest request,
      Input input
  ) {
    return Mono.just(context.validRequest());
  }

  interface Context<OUTPUT_TYPE> {

    Event invalidRequest(OUTPUT_TYPE data, String errorMessage);

    Event invalidRequest(String errorMessage);

    Event invalidRequest(EventType eventType, OUTPUT_TYPE data, String errorMessage);

    Event invalidRequest(EventType eventType, OUTPUT_TYPE data);

    Event validRequest(OUTPUT_TYPE data);

    Event validRequest();

  }

}
