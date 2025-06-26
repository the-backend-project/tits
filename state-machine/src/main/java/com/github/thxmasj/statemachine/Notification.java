package com.github.thxmasj.statemachine;

import java.time.Duration;
import java.util.UUID;

public abstract sealed class Notification permits
    Notification.IncomingRequest,
    Notification.OutgoingRequest,
    Notification.IncomingResponse,
    Notification.OutgoingResponse
{

  private final int eventNumber;
  private final String message;

  public Notification(
      int eventNumber,
      String message
  ) {
    this.eventNumber = eventNumber;
    this.message = message;
  }

  public static final class IncomingResponse extends Notification {

    private final Subscriber subscriber;
    private final boolean guaranteed;
    private final UUID requestId;

    public IncomingResponse(
        int eventNumber,
        String message,
        UUID requestId,
        Subscriber subscriber,
        boolean guaranteed
    ) {
      super(eventNumber, message);
      this.subscriber = subscriber;
      this.guaranteed = guaranteed;
      this.requestId = requestId;
    }

    public Subscriber subscriber() {
      return subscriber;
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
    private final Subscriber subscriber;
    private final UUID creatorId;
    private final boolean guaranteed;
    private final EntityId parentEntity;
    private final int maxRetryAttempts;
    private final Duration retryInterval;

    public OutgoingRequest(
        UUID id,
        int eventNumber,
        String message,
        Subscriber subscriber,
        UUID creatorId,
        boolean guaranteed,
        int maxRetryAttempts,
        Duration retryInterval,
        EntityId parentEntity
    ) {
      super(eventNumber, message);
      this.id = id;
      this.subscriber = subscriber;
      this.creatorId = creatorId;
      this.guaranteed = guaranteed;
      this.maxRetryAttempts = maxRetryAttempts;
      this.retryInterval = retryInterval;
      this.parentEntity = parentEntity;
    }

    public UUID id() {
      return id;
    }

    public Subscriber subscriber() {
      return subscriber;
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

    public OutgoingResponse(
        int eventNumber,
        String message,
        UUID requestId
    ) {
      super(eventNumber, message);
      this.requestId = requestId;
    }

    public UUID requestId() {
      return requestId;
    }

  }

  public static final class IncomingRequest extends Notification {

    private final UUID id;
    private final String messageId;
    private final String clientId;
    private final byte[] digest;

    public IncomingRequest(
        UUID id,
        int eventNumber,
        String message,
        String messageId,
        String clientId,
        byte[] digest
    ) {
      super(eventNumber, message);
      this.id = id;
      this.messageId = messageId;
      this.clientId = clientId;
      this.digest = digest;
    }

    public UUID id() {
      return id;
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

  public String message() {
    return message;
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
