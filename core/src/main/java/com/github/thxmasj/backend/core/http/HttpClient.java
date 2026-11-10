package com.github.thxmasj.backend.core.http;

import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;
import java.time.Duration;

public interface HttpClient {

  Mono<HttpResponseMessage> exchange(HttpRequestMessage message);

  class TimeoutException extends RuntimeException {

    public TimeoutException(Duration elapsedTime, Throwable cause) {
      super(String.format("Client timeout after %s", elapsedTime), cause);
    }

  }
}
