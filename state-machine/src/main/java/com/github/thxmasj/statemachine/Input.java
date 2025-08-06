package com.github.thxmasj.statemachine;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import reactor.core.publisher.Mono;

public interface Input {

  record IncomingRequest(HttpRequestMessage httpMessage, String messageId, String clientId, int eventNumber) {}
  record OutgoingRequest<T>(HttpRequestMessage httpMessage, int eventNumber) {}
  record IncomingResponse(HttpResponseMessage httpMessage, int eventNumber) {}

  <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType eventType, Class<T> type);

  List<Event> all(EventType... eventTypes);

  Event one(EventType eventType);

  Event one(EventType... eligibleEventTypes);

  Optional<Event> lastIfExists(EventType... eligibleEventTypes);

  Event last(EventType eventType);

  Event last();

  Event last(Class<?> dataType);

  Entity entity();

  List<Entity> nestedEntities();

  Entity nestedEntity(String entityName);

  SecondaryId secondaryId(String entityName, SecondaryIdModel idModel);

  ProcessResult processResult(EntityModel entityType, EntityId entityId);

  Event processedEvent(EventType eventType);

  ZonedDateTime timestamp();

}
