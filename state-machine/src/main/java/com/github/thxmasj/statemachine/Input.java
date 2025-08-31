package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;

public interface Input {

  record IncomingRequest(HttpRequestMessage httpMessage, String messageId, String clientId) {}
  record IncomingResponse(HttpResponseMessage httpMessage, int eventNumber) {}

}
