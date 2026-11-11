package com.github.thxmasj.backend.core;

import java.util.UUID;

public interface EventType<I, O> {

  String name();

  UUID id();

  DataType<I> inputDataType();

  DataType<O> outputDataType();

}
