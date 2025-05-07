package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import reactor.core.publisher.Mono;

public interface IncomingResponseValidator<OUTPUT_TYPE> extends DataRequirer {

  Mono<Result> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      HttpRequestMessage requestMessage,
      Input.IncomingResponse response,
      Input input
  );

  record Result(Status status, String message, Event event) {
    public enum Status {
      Ok,
      PermanentError,
      TransientError
    }
  }

  interface Context<OUTPUT_TYPE> {

    Event requestUndelivered(String cause);

    Event validResponse(EventType eventType, OUTPUT_TYPE data);

    Event invalidResponse(String cause);

    Event rollback(String cause);

  }

}
