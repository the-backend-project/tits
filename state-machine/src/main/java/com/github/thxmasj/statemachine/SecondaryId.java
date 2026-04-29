package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.Objects;

public class SecondaryId<T> {

  private final SecondaryIdModel<T> model;
  private final T value;
  private final long serialNumber;

  public SecondaryId(SecondaryIdModel<T> model, T value) {
      this.model = model;
      this.value = value;
      this.serialNumber = -1;
  }

  public SecondaryId(SecondaryIdModel<T> model, T value, long serialNumber) {
    this.model = model;
    this.value = value;
    this.serialNumber = serialNumber;
  }

  public SecondaryIdModel<T> model() {
    return model;
  }

  public T data() {
    return value;
  }

  public long serialNumber() {
    return serialNumber;
  }

  @Override
  public String toString() {
    return "SecondaryId{" +
        "model=" + model +
        ", value=" + value +
        ", serialNumber=" + serialNumber +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof SecondaryId<?> that))
      return false;
    return serialNumber == that.serialNumber && Objects.equals(model, that.model) && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(model, value, serialNumber);
  }
}
