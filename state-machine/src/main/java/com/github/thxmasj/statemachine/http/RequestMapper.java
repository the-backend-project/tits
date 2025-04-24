package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.IncomingRequestModelBuilder;

public interface RequestMapper {

  IncomingRequestModelBuilder<?> incomingRequest(HttpRequestMessage message);

}
