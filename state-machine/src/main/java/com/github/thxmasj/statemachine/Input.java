package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.ZonedDateTime;
import java.util.List;
import reactor.core.publisher.Mono;

public interface Input {

  record IncomingRequest(HttpRequestMessage httpMessage, String messageId, String clientId, int eventNumber) {}
  record OutgoingRequest<T>(HttpRequestMessage httpMessage, int eventNumber) {}
  record IncomingResponse(HttpResponseMessage httpMessage, int eventNumber) {}

  <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType<?, ?> eventType, Class<T> type);

  Entity entity();

  List<Entity> nestedEntities();

  Entity nestedEntity(String entityName);

  SecondaryId secondaryId(String entityName, SecondaryIdModel idModel);

  ProcessResult processResult(EntityModel entityType, EntityId entityId);

  <T> Event<T> processedEvent(EventType<?, T> eventType, Class<T> dataType);

  ZonedDateTime timestamp();

}
