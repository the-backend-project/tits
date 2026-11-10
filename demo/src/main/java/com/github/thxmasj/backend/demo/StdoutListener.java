package com.github.thxmasj.backend.demo;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Listener;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/// Writes logs as an unbuffered event stream to stdout, one line per event, and leaves routing and storage of the
/// stream to the execution environment (12-factor XI).
public class StdoutListener implements Listener {

  private static void log(String correlationId, String message) {
    System.out.println("[" + correlationId + "] " + message);
  }

  @Override
  public void changeAccepted(String correlationId, List<Change> changes) {
    log(correlationId, "Changes accepted: " + changes.stream()
        .map(c -> c.entity() + (c.event() != null ? " " + c.event().number() + ":" + c.event().type() : ""))
        .collect(joining(", ")));
  }

  @Override
  public void processNextDeadlineFailed(Throwable t) {
    log("-", "Process next deadline failed: " + t);
  }

  @Override
  public void forwardingAttempt(UUID requestId, EntityModel entityModel, String queue, EntityId entityId, int eventNumber, ZonedDateTime enqueuedAt, int attempt, String correlationId) {
    log(correlationId, "Forwarding attempt " + attempt + " from " + queue);
  }

  @Override
  public void forwardingCompleted(UUID requestId, EntityModel entityModel, String queue, EntityId entityId, int eventNumber, ZonedDateTime enqueuedAt, int attempt, String correlationId, HttpResponseMessage receipt, String reason) {
    log(correlationId, "Forwarding from " + queue + " completed: " + reason);
  }

  @Override
  public void forwardingBackedOff(UUID requestId, EntityModel entityModel, String queue, EntityId entityId, int eventNumber, ZonedDateTime enqueuedAt, int attempt, String correlationId, String reason, ZonedDateTime nextAttemptAt, Duration backoff) {
    log(correlationId, "Forwarding from " + queue + " backed off " + backoff + ": " + reason);
  }

  @Override
  public void forwardingDead(UUID requestId, EntityModel entityModel, String queue, EntityId entityId, int eventNumber, ZonedDateTime enqueuedAt, int attempt, String correlationId, String reason) {
    log(correlationId, "Forwarding from " + queue + " is dead: " + reason);
  }

  @Override
  public void forwardingDeadByExhaustion(UUID requestId, EntityModel entityModel, String queue, EntityId entityId, int eventNumber, ZonedDateTime enqueuedAt, int attempt, String correlationId, String reason) {
    log(correlationId, "Forwarding from " + queue + " is dead by exhaustion: " + reason);
  }

  @Override
  public void forwardingDeadlock(String queue) {
    log("-", "Forwarding deadlock on " + queue);
  }

  @Override
  public void forwardingError(String queue, Throwable error) {
    log("-", "Forwarding error on " + queue + ": " + error);
  }

  @Override
  public void forwardingEmptyQueue(String queue) {}

}
