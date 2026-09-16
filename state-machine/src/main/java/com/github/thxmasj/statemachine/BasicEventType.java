package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import java.util.Objects;
import java.util.UUID;

public class BasicEventType<I, O> implements EventType<I, O> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<O> outputDataType;

  protected BasicEventType(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    assert name != null;
    assert id != null;
    assert inputDataType != null;
    assert outputDataType != null;
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
    this.outputDataType = outputDataType;
  }

  public static <I, O> EventType<I, O> of(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
    return new BasicEventType<>(name, id, inputDataType, outputDataType);
  }

  public static EventType<Void, Void> of(String name, UUID id) {
    return of(name, id, DataType.none(), DataType.none());
  }

  public static <T> EventType<T, T> of(String name, UUID id, DataType<T> dataType) {
    return of(name, id, dataType, dataType);
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
    if (!(o instanceof BasicEventType<?, ?> that))
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

  public static class Rollback extends BasicEventType<Data, Data> {
    public Rollback(String name, UUID id) {
      super(name, id, DataType.forClass(Data.class), DataType.forClass(Data.class));
    }

    public record Data(
        int toNumber,
        int fromNumber,
        String reason
    ) {}
  }

  public static class Cancel extends Rollback {

    public Cancel(String name, UUID id) {
      super(name, id);
    }
  }

  public static class ReadOnly<I, O> extends BasicEventType<I, O> {

    public ReadOnly(String name, UUID id, DataType<I> inputDataType, DataType<O> outputDataType) {
      super(name, id, inputDataType, outputDataType);
    }
  }

  public static class DataLess extends BasicEventType<Void, Void> {

    public DataLess(String name, UUID id) {
      super(name, id, DataType.none(), DataType.none());
    }
  }

}
