package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;

public interface Input {

  record IncomingRequest(HttpRequestMessage httpMessage, String messageId, String clientId) {}
  record IncomingResponse(HttpResponseMessage httpMessage, int eventNumber) {}

}
