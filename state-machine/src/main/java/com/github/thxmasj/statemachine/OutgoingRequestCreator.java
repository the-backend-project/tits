package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface OutgoingRequestCreator<T> extends DataRequirer {

  Mono<String> create(
      T data,
      EntityId entityId,
      String correlationId,
      Input input
  );

}
