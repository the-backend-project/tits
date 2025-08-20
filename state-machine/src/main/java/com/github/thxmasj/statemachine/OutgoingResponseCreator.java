package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.OutgoingRequestCreator.Context;
import com.github.thxmasj.statemachine.message.Message;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface OutgoingResponseCreator<T> {

  Mono<HttpResponseMessage> create(T data, ResponseContext context);

  interface ResponseContext extends Context {
    Message.IncomingRequest incomingRequest();
  }

}
