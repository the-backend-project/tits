package com.github.thxmasj.statemachine;

import java.util.UUID;

public record EventType<I, O>(
    String name,
    UUID id,
    DataType<I> inputDataType,
    DataType<O> outputDataType,
    boolean isRollback,
    boolean isCancel,
    boolean isReadOnly
) {

  public static class DataType<T> {
    private final Class<T> clazz;
    private final String name;

    public DataType(Class<T> clazz) {
      this.clazz = clazz;
      this.name = clazz.getSimpleName();
    }

    public <T1, T2> DataType(Class<T1> t1Type, Class<T2> t2Type) {
      this.clazz = null;
      this.name = "(" + t1Type.getSimpleName() + ", " + t2Type.getSimpleName() + ")";
    }

    public Class<T> value() {
      return clazz;
    }

    public String name() {
      return clazz != null ? clazz.getSimpleName() : name;
    }
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, Class<I> inputDataType, Class<O> outputDataType) {
    return new EventType<>(name, id, new DataType<>(inputDataType), new DataType<>(outputDataType), false, false, false);
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, Class<O> outputDataType) {
    return new EventType<>(name, id, inputDataType, new DataType<>(outputDataType), false, false, false);
  }

  public static <T> EventType<T, T> of(String name, UUID id, Class<T> dataType) {
    return new EventType<>(name, id, new DataType<>(dataType), new DataType<>(dataType), false, false, false);
  }

  public static EventType<Void, Void> of(String name, UUID id) {
    return of(name, id, Void.class, Void.class);
  }

  public static EventType<Void, Long> rollback(String name, UUID id) {
    return new EventType<>(name, id, new DataType<>(Void.class), new DataType<>(Long.class), true, false, false);
  }

  public static EventType<Void, Void> cancel(String name, UUID id) {
    return new EventType<>(name, id, new DataType<>(Void.class), new DataType<>(Void.class), true, true, false);
  }

  public static <T> EventType<T, T> readOnly(String name, UUID id, Class<T> dataType) {
    return new EventType<>(name, id, new DataType<>(dataType), new DataType<>(dataType), false, false, true);
  }

}
