package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;

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
}
