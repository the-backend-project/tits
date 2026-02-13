package com.github.thxmasj.statemachine.templates.cardpayment;

import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.Listener;
import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public class Logger implements Listener {

  private final String entityName;

  public Logger(String entityName) {
    this.entityName = entityName;
  }

  @Override
  public void clientRequestFailed(String correlationId, EventType<?, ?> eventType, Throwable t) {
    log(header("N/A", correlationId) + " Request failed: " + t.toString());
    //noinspection CallToPrintStackTrace
    t.printStackTrace();
  }

  private void log(String message) {
    System.out.println(message);
  }

  private String header(Object entityId, String correlationId) {
    return String.format("[%s %s %s]", entityName, entityId, correlationId);
  }

  @Override
  public void rollbackFailed(String correlationId, EntityId entityId, Throwable t) {
    log(header(entityId, correlationId) + " Rollback failed");
  }

  @Override
  public void inconsistentState(
          String correlationId,
          EntityId entityId,
          String details
  ) {
    log(header(entityId, correlationId) + " Inconsistent state");
  }

  @Override
  public void resolveStateFailed(
      String correlationId,
      EntityId entityId,
      String sourceState,
      EventType<?, ?> resolveEvent,
      String details
  ) {
    log(header(entityId, correlationId) + " Resolving state " + sourceState + " with " + resolveEvent.name() + " failed: " + details);
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    log("[" + correlationId + "] Change set accepted: \n" + toString(changes));
  }

  private String toString(List<Change> changes) {
    try {
      return new ObjectMapper()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
          .writeValueAsString(changes);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void changeFailed(String correlationId, List<Change> changes, Throwable t) {
    log("[" + correlationId + "] Change set failed with [" + t.toString() + "]\n" + toString(changes));
  }

  @Override
  public void repeatedRequest(String correlationId, EntityId entityId, String clientId, String messageId) {
    log(header(entityId, correlationId) + " Repeated request: clientId=" + clientId + ", messageId=" + messageId);
  }

  @Override
  public void changeRaced(String correlationId, List<Change> changes, ChangeRaced cause) {
    log(header(changes.getLast().entity().id(), correlationId) + " Change raced on table " + cause.tableName() + "\n" + changes.stream().map(Change::toString).collect(joining("\n  ")));
  }

  @Override
  public void processNextDeadlineFailed(Throwable t) {
    log("Process next deadline failed: " + t.toString());
  }

  @Override
  public void forwardingAttempt(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId
  ) {
    log("Forwarding attempt: " + requestId);
  }

  @Override
  public void forwardingCompleted(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      HttpResponseMessage receipt,
      String reason
  ) {
    log("Forwarding completed: " + requestId);
  }

  @Override
  public void forwardingBackedOff(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason,
      ZonedDateTime nextAttemptAt,
      Duration backoff
  ) {
    log(header(entityId, correlationId) + " Forwarding backed off: " + requestId +", Reason: " + reason);
  }

  @Override
  public void forwardingDead(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  ) {
    log(header(entityId, correlationId) + " Forwarding failed: " + requestId + ", Reason: " + reason);
  }

  @Override
  public void forwardingDeadByExhaustion(
      UUID requestId,
      EntityModel entityModel,
      String queue,
      EntityId entityId,
      int eventNumber,
      ZonedDateTime enqueuedAt,
      int attempt,
      String correlationId,
      String reason
  ) {
    log(header(entityId, correlationId) + " Forwarding failed after maximum attempt time: " + requestId + ", Reason: " + reason);
  }

  @Override
  public void forwardingDeadlock(String queue) {
    log("Forwarding deadlock");
  }

  @Override
  public void forwardingError(String queue, Throwable error) {
    log("Forwarding error for queue " + queue + ": " + error.toString());
  }

  @Override
  public void forwardingEmptyQueue(String queue) {
    log("Forwarding from " + queue + ": empty");
  }

}
