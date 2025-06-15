package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;
import java.util.UUID;

public interface OutgoingRequestCreator<T> extends DataRequirer {

  Mono<String> create(
      T data,
      EntityId entityId,
      String correlationId,
      Input input
  );

  default String repeated(String message) {
    return message;
  }

  UUID id();

}
