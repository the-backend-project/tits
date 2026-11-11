package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.*;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.http.HttpDataType;
import com.github.thxmasj.backend.core.message.http.*;

import java.util.*;

public class ResponseEventType<I> implements EventType<I, Tuple2<HttpResponseMessage, EventReference>> {

  private final String name;
  private final UUID id;
  private final DataType<I> inputDataType;
  public static final DataType<Tuple2<HttpResponseMessage, EventReference>> outputDataType = DataType.tuple(
      HttpDataType.forResponse(),
      DataType.eventReference()
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
