package com.github.thxmasj.statemachine;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public sealed interface ProcessResult<T> permits ProcessResult.Accepted, ProcessResult.Completed, ProcessResult.Pending, ProcessResult.Rejected, ProcessResult.UnknownId {

  record Completed<T>(ProcessResult<?> choiceResult, EntityId entityId, int eventNumber, EntityModel entityModel) implements ProcessResult<T> {
    public Completed<T> completed() {return this;}
  }

  /**
   * @param triggerType optional type of the stored trigger that caused the event
   * @param triggerId   id of the stored trigger that caused the event, present if and only if triggerType is
   */
  record Accepted<T>(Event<T> event, EntityModel entityModel, TriggerType triggerType, UUID triggerId) implements ProcessResult<T> {
    public Accepted<T> accepted() {return this;}
  }

  record Rejected<T>(EventType<?, T> eventType, RejectedEvent exception, AtomicBoolean handled) implements ProcessResult<T> {

    public Accepted<T> accepted() {
      System.out.println("accepted() called on ProcessResult.Rejected");
      throw exception;
    }

    public Rejected<T> rejected() {return this;}

    @Override
    public boolean isRejected() {
      handled.set(true);
      return true;
    }

    public boolean isHandled() {
      return handled.get();
    }
  }

  record UnknownId<T>(EventType<?, T> eventType, UnknownEntity exception) implements ProcessResult<T> {

    public UnknownId<T> unknownId() {return this;}
  }

  record Pending<T, I, O>(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) implements ProcessResult<O> {

    @Override
    public Pending<?, ?, O> pending() {
      return this;
    }
  }

  record Entity(
      EntityId id,
      List<SecondaryId<?>> secondaryIds,
      EntityModel model
  ) {}

  static <T> Completed<T> completed(ProcessResult<?> choiceResult, EntityId entityId, int eventNumber, EntityModel entityModel) {
    return new Completed<>(choiceResult, entityId, eventNumber, entityModel);
  }

  default boolean isCompleted() {
    return this instanceof Completed;
  }

  default Completed<T> completed() {
    throw new IllegalStateException("Result is " + this);
  }

  static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel) {
    return new Accepted<>(event, entityModel, null, null);
  }

  static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel, TriggerType triggerType, UUID triggerId) {
    return new Accepted<>(event, entityModel, triggerType, triggerId);
  }

  default boolean isAccepted() {
    return this instanceof Accepted;
  }

  default Accepted<T> accepted() {
    throw new IllegalStateException("Result is " + this);
  }

  static <T> Rejected<T> rejected(EventType<?, T> eventType, RejectedEvent exception) {
    return new Rejected<>(eventType, exception, new AtomicBoolean());
  }

  default boolean isRejected() {
    return this instanceof Rejected;
  }

  default Rejected<T> rejected() {
    throw new IllegalStateException("Result is " + this);
  }

  static <T> UnknownId<T> unknownId(EventType<?, T> eventType, UnknownEntity exception) {
    return new UnknownId<>(eventType, exception);
  }

  default boolean isPending() {
    return this instanceof Pending;
  }

  default Pending<?, ?, T> pending() {
    throw new IllegalStateException("Result is " + this);
  }

  static <T, I, O> Pending<T, I, O> pending(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) {
    return new Pending<>(trigger, inputData, exception);
  }

  default boolean isUnknownId() {
    return this instanceof UnknownId;
  }

  default UnknownId<T> unknownId() {
    return (UnknownId<T>) this;
  }

}
