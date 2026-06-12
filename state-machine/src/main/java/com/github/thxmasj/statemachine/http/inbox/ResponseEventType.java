package com.github.thxmasj.statemachine.http.inbox;

import com.github.thxmasj.statemachine.*;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.EventReference;
import com.github.thxmasj.statemachine.message.http.*;

import java.util.*;

public class ResponseEventType<I> implements EventType<I, ResponseEventType.Data> {

  public record Data(HttpResponseMessage response, EventReference processReference) {}

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<Data> outputDataType = new DataType<>(Data.class);

  public ResponseEventType(String name, UUID id, DataType<I> inputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
  }

  public ResponseEventType(String name, UUID id, Class<I> inputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = new DataType<>(inputDataType);
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
  public DataType<Data> outputDataType() {
    return outputDataType;
  }
}
