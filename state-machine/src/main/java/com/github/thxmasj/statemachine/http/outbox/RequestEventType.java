package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.UUID;

public class RequestEventType<I> implements EventType<I, HttpRequestMessage> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  private final DataType<HttpRequestMessage> outputDataType = new DataType<>(HttpRequestMessage.class);

  public RequestEventType(String name, UUID id, DataType<I> inputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
  }

  public RequestEventType(String name, UUID id, Class<I> inputDataType) {
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
  public DataType<HttpRequestMessage> outputDataType() {
    return outputDataType;
  }
}
