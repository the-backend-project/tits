package com.github.thxmasj.statemachine;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.UUID;

public interface EventType<I, O> {

  String name();

  UUID id();

  DataType<I> inputDataType();

  DataType<O> outputDataType();

  class DataType<T> {

    private final Class<T> clazz;
    private final TypeReference<T> typeReference;
    private final String name;

    public DataType(Class<T> clazz) {
      this.clazz = clazz;
      this.typeReference = new TypeReference<>() {};
      this.name = clazz != null ? clazz.getSimpleName() : "?";
    }

    public DataType(TypeReference<T> typeReference, Class<T> clazz) {
      this.clazz = clazz;
      this.typeReference = typeReference;
      this.name = clazz != null ? clazz.getSimpleName() : "?";
    }

    public <T1, T2> DataType(TypeReference<T> typeReference, Class<T1> t1Type, Class<T2> t2Type) {
      this.clazz = null;
      this.typeReference = typeReference;
      this.name = "(" + name(t1Type) + ", " + name(t2Type) + ")";
    }

    public <T1, T2, T3> DataType(TypeReference<T> typeReference, Class<T1> t1Type, Class<T2> t2Type, Class<T3> t3Type) {
      this.clazz = null;
      this.typeReference = typeReference;
      this.name = "(" + name(t1Type) + ", " + name(t2Type) + ", " + name(t3Type) + ")";
    }

    public <T1, T2, T3, T4> DataType(TypeReference<T> typeReference, Class<T1> t1Type, Class<T2> t2Type, Class<T3> t3Type, Class<T4> t4Type) {
      this.clazz = null;
      this.typeReference = typeReference;
      this.name = "(" + name(t1Type) + ", " + name(t2Type) + ", " + name(t3Type) + ", " + name(t4Type) + ")";
    }

    public <T1, T2, T3, T4, T5> DataType(TypeReference<T> typeReference, Class<T1> t1Type, Class<T2> t2Type, Class<T3> t3Type, Class<T4> t4Type, Class<T5> t5Type) {
      this.clazz = null;
      this.typeReference = typeReference;
      this.name = "(" + name(t1Type) + ", " + name(t2Type) + ", " + name(t3Type) + ", " + name(t4Type) + ", " + name(t5Type) + ")";
    }

    public static <T> DataType<T> unknown() {
      return new DataType<>(null);
    }

    public Class<T> value() {
      return clazz;
    }

    public String name() {
      return name;
    }

    public TypeReference<T> typeReference() {
      return typeReference;
    }

    private String name(Class<?> clazz) {
      return clazz != null ? clazz.getSimpleName() : "?";
    }

  }

}
