package com.github.thxmasj.statemachine;

import java.util.UUID;

public interface EventType<I, O> {

  String name();

  UUID id();

  DataType<I> inputDataType();

  DataType<O> outputDataType();

  class DataType<T> {

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
      return clazz != null ? (clazz == Void.class ? "-" : clazz.getSimpleName()) : name;
    }
  }
}
