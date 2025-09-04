package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import java.util.UUID;

public class BasicEventType<I, O> implements EventType<I, O> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<O> outputDataType;

  protected BasicEventType(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
    this.outputDataType = outputDataType;
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, Class<I> inputDataType, Class<O> outputDataType) {
    return new BasicEventType<>(name, id, new DataType<>(inputDataType), new DataType<>(outputDataType));
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, Class<O> outputDataType) {
    return new BasicEventType<>(name, id, inputDataType, new DataType<>(outputDataType));
  }

  public static <T> EventType<T, T> of(String name, UUID id, Class<T> dataType) {
    return new BasicEventType<>(name, id, new DataType<>(dataType), new DataType<>(dataType));
  }

  public static EventType<Void, Void> of(String name, UUID id) {
    return of(name, id, Void.class, Void.class);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public DataType<I> inputDataType() {
    return inputDataType;
  }

  @Override
  public DataType<O> outputDataType() {
    return outputDataType;
  }

  public static class Rollback extends BasicEventType<Void, Data> {
    public Rollback(String name, UUID id) {
      super(name, id, new DataType<>(Void.class), new DataType<>(Data.class));
    }

    public record Data(
        int toNumber,
        String reason
    ) {}
  }

  public static class Cancel extends Rollback {

    public Cancel(String name, UUID id) {
      super(name, id);
    }
  }

  public static class ReadOnly<O> extends BasicEventType<Void, O> {

    public ReadOnly(String name, UUID id, Class<O> outputDataType) {
      super(name, id, new DataType<>(Void.class), new DataType<>(outputDataType));
    }
  }

  public static class DataLess extends BasicEventType<Void, Void> {

    public DataLess(String name, UUID id) {
      super(name, id, new DataType<>(Void.class), new DataType<>(Void.class));
    }
  }

}
