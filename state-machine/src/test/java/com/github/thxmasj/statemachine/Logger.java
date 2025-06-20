package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.*;
import java.time.*;
import java.util.*;

import static java.util.stream.Collectors.*;

public class Logger implements Listener {

  private final String entityName;

  public Logger(String entityName) {
    this.entityName = entityName;
  }

  @Override
  public void clientRequestFailed(String correlationId, EntityId entityId, Event event, Throwable t) {
    log(header(entityId, correlationId) + " Request failed: " + t.toString());
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
  public void actionExecuted(String correlationId, EntityId entityId, String actionName, String currentState, Event output) {
    log(header(entityId, correlationId) + " Action executed: " + actionName);
  }

  @Override
  public void inconsistentState(
          String correlationId,
          EntityId entityId,
          String sourceState,
          String details
  ) {
    log(header(entityId, correlationId) + " Inconsistent state");
  }

  @Override
  public void resolveStateFailed(
      String correlationId,
      EntityId entityId,
      String sourceState,
      Event resolveEvent,
      String details
  ) {
    log(header(entityId, correlationId) + " Resolving state " + sourceState + " with " + resolveEvent.getTypeName() + " failed: " + details);
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    log("[" + correlationId + "] Change accepted: \n  " + changes.stream().map(Change::toString).collect(joining("\n  ")));
  }

  @Override
  public void changeFailed(String correlationId, List<Change> changes, Throwable t) {
    log("[" + correlationId + "] Change failed: \n  " + changes.stream().map(Change::toString).collect(joining("\n  ")) + "\nReason: " + t);
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
  public void forwardingAttempt(String subscriber, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId) {
    log("Forwarding attempt");
  }

  @Override
  public void forwardingCompleted(String subscriber, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId, String receipt, String reason) {
    log("Forwarding completed");
  }

  @Override
  public void forwardingBackedOff(String subscriber, ZonedDateTime enqueuedAt, int attempt, EntityId entityId, int eventNumber, String correlationId, String reason, ZonedDateTime nextAttemptAt, Duration backoff) {
    log(header(entityId, correlationId) + " Forwarding to " + subscriber + " backed off: " + reason);
  }

  @Override
  public void forwardingDead(EntityId entityId, String subscriber, ZonedDateTime enqueuedAt, int attempt, int eventNumber, String correlationId, String reason) {
    log(header(entityId, correlationId) + " Forwarding to " + subscriber + " failed: " + reason);
  }

  @Override
  public void forwardingDeadByExhaustion(EntityId entityId, String subscriber, ZonedDateTime enqueuedAt, int attempt, int eventNumber, String correlationId, String reason) {
    log(header(entityId, correlationId) + " Forwarding to " + subscriber + " failed after maximum attempt time: " + reason);
  }

  @Override
  public void forwardingRaced(String subscriber) {
    log("Forwarding raced");
  }

  @Override
  public void forwardingDeadlock(String subscriber) {
    log("Forwarding deadlock");
  }

  @Override
  public void forwardingError(String subscriber, Throwable error) {
    log("Forwarding error for subscriber " + subscriber + ": " + error.toString());
  }

  @Override
  public void forwardingEmptyQueue(String subscriber) {
    log("Forwarding to " + subscriber + ": empty queue");
  }

}
