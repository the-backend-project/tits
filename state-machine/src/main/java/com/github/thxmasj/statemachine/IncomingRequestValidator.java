package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface IncomingRequestValidator<OUTPUT_TYPE> {

  sealed interface Result permits Result.Valid, Result.Invalid {
    record Valid<OUTPUT_TYPE>(InputEvent<OUTPUT_TYPE> value) implements Result {}
    record Invalid(InputEvent<String> error) implements Result {}
  }

  default Mono<Result> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingRequest request
  ) {
    return Mono.just(context.validRequest());
  }

  interface Context<OUTPUT_TYPE> {

    Result invalidRequest(String errorMessage);

    Result invalidRequest(EventType<OUTPUT_TYPE, ?> eventType, OUTPUT_TYPE data, String errorMessage);

    Result invalidRequest(EventType<OUTPUT_TYPE, ?> eventType, OUTPUT_TYPE data);

    Result validRequest(OUTPUT_TYPE data);

    Result validRequest();

  }

}
