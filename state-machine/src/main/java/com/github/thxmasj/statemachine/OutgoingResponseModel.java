package com.github.thxmasj.statemachine;

import java.util.function.Function;

@SuppressWarnings("unused")
public record OutgoingResponseModel<T, U>(
    Function<T, U> dataAdapter,
    Class<? extends OutgoingResponseCreator<U>> creatorType,
    OutgoingResponseCreator<U> creator
) {

  public static <T, U> OutgoingResponseModel<T, U> response(OutgoingResponseCreator<U> responseCreator) {
    return new OutgoingResponseModel<>((T _) -> null, null, responseCreator);
  }

  public static <T, U> OutgoingResponseModel<T, U> response(U input, OutgoingResponseCreator<U> responseCreator) {
    return new OutgoingResponseModel<>((T _) -> input, null, responseCreator);
  }

}
