package com.github.thxmasj.statemachine;

import java.util.Objects;
import java.util.UUID;

public class RequestEventType<I, O> implements EventType<I, O> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<O> outputDataType;

  protected RequestEventType(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
    this.outputDataType = outputDataType;
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, Class<I> inputDataType, Class<O> outputDataType) {
    return new RequestEventType<>(name, id, new DataType<>(inputDataType), new DataType<>(outputDataType));
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, Class<O> outputDataType) {
    return new RequestEventType<>(name, id, inputDataType, new DataType<>(outputDataType));
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    return new RequestEventType<>(name, id, inputDataType, outputDataType);
  }

  public static <T> EventType<T, T> of(String name, UUID id, Class<T> dataType) {
    return new RequestEventType<>(name, id, new DataType<>(dataType), new DataType<>(dataType));
  }

  public static EventType<Void, Void> of(String name, UUID id) {
    return of(name, id, Void.class, Void.class);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String toString() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof RequestEventType<?, ?> that))
      return false;
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }

  @Override
  public DataType<I> inputDataType() {
    return inputDataType;
  }

  @Override
  public DataType<O> outputDataType() {
    return outputDataType;
  }

}
