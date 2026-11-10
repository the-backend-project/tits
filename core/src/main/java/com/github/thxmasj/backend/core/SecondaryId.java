package com.github.thxmasj.backend.core;

import java.util.Objects;

public class SecondaryId<T> {

  private final SecondaryIdModel<T> model;
  private final T value;

  public SecondaryId(SecondaryIdModel<T> model, T value) {
    this.model = model;
    this.value = value;
  }

  public SecondaryIdModel<T> model() {
    return model;
  }

  public T data() {
    return value;
  }

  public byte[] marshalledData() {
    return model.dataType().marshal(value);
  }

  @Override
  public String toString() {
    return "SecondaryId{" +
        "model=" + model +
        ", value=" + value +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SecondaryId<?> that))
      return false;
    return Objects.equals(model, that.model) && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(model, value);
  }
}
