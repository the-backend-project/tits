package com.github.thxmasj.statemachine;

public record IncomingResponseModel(
    Class<? extends IncomingResponseValidator<?>> responseValidator
) {

  public static IncomingResponseModelBuilder<?> responseValidator(Class<? extends IncomingResponseValidator<?>> validator) {
    return new IncomingResponseModelBuilder<>().validator(validator);
  }

}
