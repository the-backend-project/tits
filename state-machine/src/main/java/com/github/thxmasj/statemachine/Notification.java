package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.Duration;
import java.util.UUID;

public abstract sealed class Notification permits
    Notification.IncomingRequest,
    Notification.OutgoingRequest,
    Notification.IncomingResponse,
    Notification.OutgoingResponse
{

  private final int eventNumber;

  public Notification(
      int eventNumber
  ) {
    this.eventNumber = eventNumber;
  }

  public static final class IncomingResponse extends Notification {

    private final HttpResponseMessage message;
    private final OutboxQueue queue;
    private final boolean guaranteed;
    private final UUID requestId;

    public IncomingResponse(
        int eventNumber,
        HttpResponseMessage message,
        UUID requestId,
        OutboxQueue queue,
        boolean guaranteed
    ) {
      super(eventNumber);
      this.message = message;
      this.queue = queue;
      this.guaranteed = guaranteed;
      this.requestId = requestId;
    }

    public HttpResponseMessage message() {
      return message;
    }

    public OutboxQueue queue() {
      return queue;
    }

    public boolean guaranteed() {
      return guaranteed;
    }

    public UUID requestId() {
      return requestId;
    }

  }

  public static final class OutgoingRequest extends Notification {

    private final UUID id;
    private final HttpRequestMessage message;
    private final OutboxQueue queue;
    private final UUID creatorId;
    private final boolean guaranteed;
    private final EntityId parentEntity;
    private final int maxRetryAttempts;
    private final Duration retryInterval;

    public OutgoingRequest(
        UUID id,
        int eventNumber,
        HttpRequestMessage message,
        OutboxQueue queue,
        UUID creatorId,
        boolean guaranteed,
        int maxRetryAttempts,
        Duration retryInterval,
        EntityId parentEntity
    ) {
      super(eventNumber);
      this.id = id;
      this.message = message;
      this.queue = queue;
      this.creatorId = creatorId;
      this.guaranteed = guaranteed;
      this.maxRetryAttempts = maxRetryAttempts;
      this.retryInterval = retryInterval;
      this.parentEntity = parentEntity;
    }

    public UUID id() {
      return id;
    }

    public HttpRequestMessage message() {
      return message;
    }

    public OutboxQueue queue() {
      return queue;
    }

    public UUID creatorId() {
      return creatorId;
    }

    public boolean guaranteed() {
      return guaranteed;
    }

    public int maxRetryAttempts() {
      return maxRetryAttempts;
    }

    public Duration retryInterval() {
      return retryInterval;
    }

    public EntityId parentEntity() {
      return parentEntity;
    }

  }

  public static final class OutgoingResponse extends Notification {

    private final UUID requestId;
    private final HttpResponseMessage message;

    public OutgoingResponse(
        int eventNumber,
        HttpResponseMessage message,
        UUID requestId
    ) {
      super(eventNumber);
      this.requestId = requestId;
      this.message = message;
    }

    public UUID requestId() {
      return requestId;
    }

    public HttpResponseMessage message() {
      return message;
    }

  }

  public static final class IncomingRequest extends Notification {

    private final UUID id;
    private final HttpRequestMessage message;
    private final String messageId;
    private final String clientId;
    private final byte[] digest;

    public IncomingRequest(
        UUID id,
        int eventNumber,
        HttpRequestMessage message,
        String messageId,
        String clientId,
        byte[] digest
    ) {
      super(eventNumber);
      this.id = id;
      this.message = message;
      this.messageId = messageId;
      this.clientId = clientId;
      this.digest = digest;
    }

    public UUID id() {
      return id;
    }

    public HttpRequestMessage message() {
      return message;
    }

    public String messageId() {
      return messageId;
    }

    public String clientId() {
      return clientId;
    }

    public byte[] digest() {
      return digest;
    }

  }

  public int eventNumber() {
    return eventNumber;
  }

  public enum Type {
    OutgoingRequest(0),
    OutgoingReverseRequest(2),
    OutgoingResponse(6),
    IncomingRequest(7);

    private final int value;

    Type(int value) {
      this.value = value;
    }

    public int value() {
      return value;
    }

  }

}
