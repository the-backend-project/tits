package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface HttpClient {

  Mono<HttpResponseMessage> exchange(HttpRequestMessage message);

  class TimeoutException extends RuntimeException {

    public TimeoutException(Throwable cause) {
      super(cause);
    }

  }
}
