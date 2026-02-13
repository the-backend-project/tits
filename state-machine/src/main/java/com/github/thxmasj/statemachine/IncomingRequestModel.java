package com.github.thxmasj.statemachine;

public record IncomingRequestModel<T, I, O>(
    boolean matches,
    EventTrigger<T, I, O> eventTrigger,
    String messageId,
    boolean derivedMessageId,
    String clientId,
    String correlationId,
    Class<? extends IncomingRequestValidator<T>> validatorClass,
    IncomingRequestValidator<T> validator,
    byte[] digest
) {}
