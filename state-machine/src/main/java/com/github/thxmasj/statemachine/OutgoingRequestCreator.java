package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.time.ZonedDateTime;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface OutgoingRequestCreator<T> {

  default HttpRequestMessage create(T data, Context context) {
    throw new UnsupportedOperationException("create not supported by " + getClass().getName());
  }

  default Mono<HttpRequestMessage> createReactive(T data, Context context) {
    return Mono.just(create(data, context));
  }

  default HttpRequestMessage reversed(T data, ReversalContext context) {
    return create(data, context);
  }

  default Mono<HttpRequestMessage> reversedReactive(T data, ReversalContext context) {
    return Mono.just(reversed(data, context));
  }

  default HttpRequestMessage repeated(HttpRequestMessage message) {
    return message;
  }

  default Mono<HttpRequestMessage> repeatedReactive(HttpRequestMessage message) {
    return Mono.just(repeated(message));
  }

  UUID id();

  default String name() {
    return getClass().getSimpleName();
  };

  interface Context {
    EntityId entityId();
    String correlationId();
    ZonedDateTime timestamp();
  }

  interface ReversalContext extends Context {
    HttpRequestMessage originalRequest();
  }

  static Context context(
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return new Context() {
      @Override
      public EntityId entityId() {
        return entityId;
      }
      @Override
      public String correlationId() {
        return correlationId;
      }
      @Override
      public ZonedDateTime timestamp() {
        return timestamp;
      }
    };
  }

  static ReversalContext reversalContext(
      HttpRequestMessage originalRequest,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return new ReversalContext() {
      @Override
      public HttpRequestMessage originalRequest() {
        return originalRequest;
      }
      @Override
      public EntityId entityId() {
        return entityId;
      }
      @Override
      public String correlationId() {
        return correlationId;
      }
      @Override
      public ZonedDateTime timestamp() {
        return timestamp;
      }
    };
  }

}
