package com.github.thxmasj.statemachine;

public record IncomingRequestModel(
    boolean matches,
    EventTriggerBuilder eventTrigger,
    String messageId,
    boolean derivedMessageId,
    String clientId,
    String correlationId,
    Class<? extends IncomingRequestValidator<?>> validatorClass,
    IncomingRequestValidator<?> validator,
    byte[] digest
) {

  public static <T> IncomingRequestModelBuilder<T> validator(Class<? extends IncomingRequestValidator<?>> validator) {
    return new IncomingRequestModelBuilder<T>().validator(validator);
  }

  public static <T> IncomingRequestModelBuilder<T> validator(IncomingRequestValidator<?> validator) {
    return new IncomingRequestModelBuilder<T>().validator(validator);
  }

}
