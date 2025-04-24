package com.github.thxmasj.statemachine;

import reactor.core.publisher.Mono;

public interface OutgoingResponseCreator<T> extends DataRequirer {

  Mono<String> create(
      T data,
      Notification.IncomingRequest incomingRequest,
      EntityId entityId,
      String correlationId,
      Input input
  );

}
