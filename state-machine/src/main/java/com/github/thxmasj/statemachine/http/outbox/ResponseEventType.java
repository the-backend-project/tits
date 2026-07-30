package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.util.UUID;

public class ResponseEventType<O> implements EventType<HttpResponseMessage, O> {

  private final String name;
  private final UUID id;
  private final DataType<O> outputDataType;
  private final DataType<HttpResponseMessage> inputDataType = new DataType<>(HttpResponseMessage.class);

  public ResponseEventType(String name, UUID id, DataType<O> outputDataType) {
    this.name = name;
    this.id = id;
    this.outputDataType = outputDataType;
  }

  public ResponseEventType(String name, UUID id, Class<O> outputDataType) {
    this.name = name;
    this.id = id;
    this.outputDataType = new DataType<>(outputDataType);
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
  public DataType<O> outputDataType() {
    return outputDataType;
  }

  @Override
  public DataType<HttpResponseMessage> inputDataType() {
    return inputDataType;
  }
}
