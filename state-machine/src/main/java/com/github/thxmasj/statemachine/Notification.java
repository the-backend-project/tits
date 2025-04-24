package com.github.thxmasj.statemachine;

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
    private final long requestId;

    public IncomingResponse(
        int eventNumber,
        String message,
        long requestId,
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

    public long requestId() {
      return requestId;
    }

  }

  public static final class OutgoingRequest extends Notification {

    private final Subscriber subscriber;
    private final boolean guaranteed;
    private final EntityId parentEntity;

    public OutgoingRequest(
        int eventNumber,
        String message,
        Subscriber subscriber,
        boolean guaranteed,
        EntityId parentEntity
    ) {
      super(eventNumber, message);
      this.subscriber = subscriber;
      this.guaranteed = guaranteed;
      this.parentEntity = parentEntity;
    }

    public Subscriber subscriber() {
      return subscriber;
    }

    public boolean guaranteed() {
      return guaranteed;
    }

    public EntityId parentEntity() {
      return parentEntity;
    }

  }

  public static final class OutgoingResponse extends Notification {

    private final int requestEventNumber;

    public OutgoingResponse(
        int eventNumber,
        String message,
        int requestEventNumber
    ) {
      super(eventNumber, message);
      this.requestEventNumber = requestEventNumber;
    }

    public int requestEventNumber() {
      return requestEventNumber;
    }

  }

  public static final class IncomingRequest extends Notification {

    private final String messageId;
    private final String clientId;
    private final byte[] digest;

    public IncomingRequest(
        int eventNumber,
        String message,
        String messageId,
        String clientId,
        byte[] digest
    ) {
      super(eventNumber, message);
      this.messageId = messageId;
      this.clientId = clientId;
      this.digest = digest;
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
