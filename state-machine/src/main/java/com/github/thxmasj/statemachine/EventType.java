package com.github.thxmasj.statemachine;

import java.util.UUID;

public record EventType<I, O>(
    String name,
    UUID id,
    Class<I> dataType,
    Class<O> outputDataType,
    boolean isRollback,
    boolean isCancel,
    boolean isReadOnly
) {

  public EventType(String name, UUID id, Class<I> inputDataType, Class<O> outputDataType) {
    this(name, id, inputDataType, outputDataType, false, false, false);
  }

  public static <T> EventType<T, T> of(String name, UUID id, Class<T> dataType) {
    return new EventType<>(name, id, dataType, dataType);
  }

  public static EventType<Void, Void> of(String name, UUID id) {
    return new EventType<>(name, id, Void.class, Void.class);
  }

  public static EventType<Void, Void> rollback(String name, UUID id) {
    return new EventType<>(name, id, Void.class, Void.class, true, false, false);
  }

  public static EventType<Void, Void> cancel(String name, UUID id) {
    return new EventType<>(name, id, Void.class, Void.class, true, true, false);
  }

  public static <T> EventType<T, T> readOnly(String name, UUID id, Class<T> dataType) {
    return new EventType<>(name, id, dataType, dataType, false, false, true);
  }

}
