package com.github.thxmasj.statemachine;

public record IncomingRequestModel<T>(
    boolean matches,
    EventTriggerBuilder<T, ?> eventTrigger,
    String messageId,
    boolean derivedMessageId,
    String clientId,
    String correlationId,
    Class<? extends IncomingRequestValidator<T>> validatorClass,
    IncomingRequestValidator<T> validator,
    byte[] digest
) {

  public static <T> IncomingRequestModelBuilder<T> validator(Class<? extends IncomingRequestValidator<T>> validator) {
    return new IncomingRequestModelBuilder<T>().validator(validator);
  }

  public static <T> IncomingRequestModelBuilder<T> validator(IncomingRequestValidator<T> validator) {
    return new IncomingRequestModelBuilder<T>().validator(validator);
  }

}
