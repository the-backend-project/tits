package com.github.thxmasj.statemachine;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import com.github.thxmasj.statemachine.Event.LoadedEvent;
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

  int nextEventNumber();

  <T> Mono<IncomingRequest> incomingRequest(EventType eventType, Class<T> type);
  <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType eventType, Class<T> type);

  List<LoadedEvent> all(EventType... eventTypes);

  LoadedEvent one(EventType eventType);

  LoadedEvent one(EventType... eligibleEventTypes);

  Optional<LoadedEvent> oneIfExists(EventType eventType);

  LoadedEvent current(EventType eligibleEventType);

  LoadedEvent current(EventType... eligibleEventTypes);

  LoadedEvent trigger(EventType... eligibleEventTypes);

  Optional<LoadedEvent> lastIfExists(EventType... eligibleEventTypes);

  LoadedEvent last(EventType eventType);

  LoadedEvent last();

  LoadedEvent last(Class<?> dataType);

  Optional<LoadedEvent> firstIfExists(EventType eventType);

  Entity entity();

  List<Entity> nestedEntities();

  Entity nestedEntity(String entityName);

  SecondaryId secondaryId(String entityName, SecondaryIdModel idModel);

  ProcessResult processResult(EntityModel entityType, EntityId entityId);

  Event processedEvent(EventType eventType);

  ZonedDateTime timestamp();

}
