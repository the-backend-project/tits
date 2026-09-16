package com.github.thxmasj.statemachine.http.inbox;

import com.github.thxmasj.statemachine.*;
import com.github.thxmasj.statemachine.EventReference;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.http.HttpDataType;
import com.github.thxmasj.statemachine.message.http.*;

import java.util.*;

public class ResponseEventType<I> implements EventType<I, Tuple2<HttpResponseMessage, EventReference>> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  public static final DataType<Tuple2<HttpResponseMessage, EventReference>> outputDataType = DataType.forTuple(
      HttpDataType.forResponse(),
      DataType.forClass(EventReference.class)
  );

  public ResponseEventType(String name, UUID id, DataType<I> inputDataType) {
    this.name = name;
    this.id = id;
    this.inputDataType = inputDataType;
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
  public DataType<Tuple2<HttpResponseMessage, EventReference>> outputDataType() {
    return outputDataType;
  }
}
