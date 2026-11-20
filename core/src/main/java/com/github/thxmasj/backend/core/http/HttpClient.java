package com.github.thxmasj.backend.core.http;

import com.github.thxmasj.backend.core.message.http.HttpRequest;
import com.github.thxmasj.backend.core.message.http.HttpResponse;
import reactor.core.publisher.Mono;
import java.time.Duration;

public interface HttpClient {

  Mono<HttpResponse<byte[]>> exchange(HttpRequest<byte[]> message);

  class TimeoutException extends RuntimeException {

    public TimeoutException(Duration elapsedTime, Throwable cause) {
      super(String.format("Client timeout after %s", elapsedTime), cause);
    }

  }
}
