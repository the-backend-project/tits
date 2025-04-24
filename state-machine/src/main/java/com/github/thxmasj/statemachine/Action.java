package com.github.thxmasj.statemachine;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public interface Action extends DataRequirer {

  default Mono<Event> execute(EntityId entityId, Input input) {
    return Mono.empty();
  }

  default <R> Mono<R> validated(R request, Validator validator) {
    var violations = validator.validate(request);
    return violations.isEmpty() ?
        Mono.just(request) :
        Mono.error(new InvalidRequest(new ConstraintViolationException(violations).getMessage()));
  }

  default <T, U> Mono<U> validated(
      T parameter,
      Function<T, Mono<U>> validator,
      String parameterName
  ) {
    return validator.apply(parameter).switchIfEmpty(Mono.error(new InvalidRequest("Unknown " + parameterName + ": " + parameter)));
  }

  class Uninitialised extends RuntimeException {}

  class InvalidRequest extends RuntimeException {

    public InvalidRequest(String message, Throwable cause) {
      super(message, cause);
    }

    public InvalidRequest(String message) {
      super(message);
    }

  }

  class RequestRejected extends RuntimeException {

    public RequestRejected(String message, Throwable cause) {
      super(message, cause);
    }

    public RequestRejected(String message) {
      super(message);
    }

  }

  class RequestFailed extends RuntimeException {

    public RequestFailed(String message, Throwable cause) {
      super(message, cause);
    }

    public RequestFailed(String message) {
      super(message);
    }

  }

}
