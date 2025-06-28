package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import reactor.core.publisher.Mono;
import java.util.UUID;

public interface OutgoingRequestCreator<T> extends DataRequirer {

  Mono<HttpRequestMessage> create(
      T data,
      EntityId entityId,
      String correlationId,
      Input input
  );

  default HttpRequestMessage repeated(HttpRequestMessage message) {
    return message;
  }

  UUID id();

}
