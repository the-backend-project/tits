package com.github.thxmasj.statemachine;

public class IncomingResponseModelBuilder<DATA_TYPE> {

  private Class<? extends IncomingResponseValidator<?>> validator;

  public IncomingResponseModelBuilder<DATA_TYPE> validator(Class<? extends IncomingResponseValidator<?>> validator) {
    this.validator = validator;
    return this;
  }

  public IncomingResponseModel build() {
    return new IncomingResponseModel(
        validator
    );
  }

}
