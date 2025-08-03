package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface IncomingRequestValidator<OUTPUT_TYPE> {

  default Mono<InputEvent<OUTPUT_TYPE>> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingRequest request,
      Input input
  ) {
    return Mono.just(context.validRequest());
  }

  interface Context<OUTPUT_TYPE> {

    InputEvent<OUTPUT_TYPE> invalidRequest(String errorMessage);

    InputEvent<OUTPUT_TYPE> invalidRequest(EventType eventType, OUTPUT_TYPE data, String errorMessage);

    InputEvent<OUTPUT_TYPE> invalidRequest(EventType eventType, OUTPUT_TYPE data);

    InputEvent<OUTPUT_TYPE> validRequest(OUTPUT_TYPE data);

    InputEvent<OUTPUT_TYPE> validRequest();

  }

}
