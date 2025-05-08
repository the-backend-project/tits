package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface IncomingResponseValidator<OUTPUT_TYPE> extends DataRequirer {

  Mono<Result> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      HttpRequestMessage requestMessage,
      Input.IncomingResponse response,
      Input input
  );

  record EvaluatedResponse(Status status, HttpStatusReason statusReason, String message) {}

  record HttpStatusReason(int status, String reasonPhrase) {

    public String message() {
      return status + " " + reasonPhrase;
    }
  }

  static Status status(HttpResponseMessage responseMessage) {
    int c = responseMessage.statusCode();
    if (c >= 200 && c < 300) {
      return Status.Ok;
    } else if (c >= 400 && c < 500) {
      return Status.PermanentError;
    } else if (c >= 500 && c < 600) {
      return Status.TransientError;
    } else {
      return Status.PermanentError;
    }
  }

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
