package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.Message;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface OutgoingResponseCreator<T> {

  Mono<HttpResponseMessage> create(
      T data,
      Message.IncomingRequest incomingRequest,
      EntityId entityId,
      String correlationId,
      Input input
  );

}
