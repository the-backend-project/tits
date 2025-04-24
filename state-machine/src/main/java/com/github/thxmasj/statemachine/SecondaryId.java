package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;

public class SecondaryId {

  private final SecondaryIdModel model;
  private final Object value;
  private final long serialNumber;

  public SecondaryId(SecondaryIdModel model, Object value) {
      this.model = model;
      this.value = value;
      this.serialNumber = -1;
  }

  public SecondaryId(SecondaryIdModel model, Object value, long serialNumber) {
    this.model = model;
    this.value = value;
    this.serialNumber = serialNumber;
  }

  public SecondaryIdModel model() {
    return model;
  }

  public Object data() {
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
