package com.github.thxmasj.statemachine;

import java.util.function.*;

@SuppressWarnings("unused")
public record OutgoingResponseModel<T, U>(
    Function<T, U> dataAdapter,
    Class<? extends OutgoingResponseCreator<U>> creatorType,
    OutgoingResponseCreator<U> creator
) {}
