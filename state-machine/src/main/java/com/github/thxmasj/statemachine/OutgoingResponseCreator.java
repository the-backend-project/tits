package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.OutgoingRequestCreator.Context;
import com.github.thxmasj.statemachine.message.Message;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;

public interface OutgoingResponseCreator<T> {

  HttpResponseMessage create(T data, ResponseContext context);

  interface ResponseContext extends Context {
    Message.IncomingRequest incomingRequest();
  }

}
