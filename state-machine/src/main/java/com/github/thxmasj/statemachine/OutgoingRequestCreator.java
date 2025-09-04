package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Mono;

public interface OutgoingRequestCreator<T> {

  default Mono<HttpRequestMessage> create(T data, Context context) {
    throw new UnsupportedOperationException("create not supported by " + getClass().getName());
  }

  default Mono<HttpRequestMessage> reversed(T data, ReversalContext context) {
    return create(data, context);
  }

  default HttpRequestMessage repeated(HttpRequestMessage message) {
    return message;
  }

  UUID id();

  default String name() {
    return getClass().getSimpleName();
  };

  interface Context {
    EntityId entityId();
    String correlationId();
    ZonedDateTime timestamp();
    List<Entity> nestedEntities();
    Entity nestedEntity(String entityName);
    SecondaryId secondaryId(String entityName, SecondaryIdModel idModel);
    ProcessResult processResult(EntityModel entityType, EntityId entityId);
    <T> Event<T> processedEvent(EventType<?, T> eventType);
  }

  interface ReversalContext extends Context {
    HttpRequestMessage originalRequest();
  }

}
