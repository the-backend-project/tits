package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface OutgoingResponseCreator<T> extends DataRequirer {

  Mono<HttpResponseMessage> create(
      T data,
      Notification.IncomingRequest incomingRequest,
      EntityId entityId,
      String correlationId,
      Input input
  );

}
