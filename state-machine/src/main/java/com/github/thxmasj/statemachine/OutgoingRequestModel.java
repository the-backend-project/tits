package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import java.util.function.Function;
import reactor.core.publisher.Mono;

public record OutgoingRequestModel<T, U>(
    Function<T, U> dataAdapter,
    Class<? extends OutgoingRequestCreator<U>> notificationCreatorType,
    OutgoingRequestCreator<U> notificationCreator,
    Subscriber subscriber,
    boolean guaranteed,
    IncomingResponseValidator<?> responseValidator
) {

  public OutgoingRequestModel {
    if (responseValidator == null)
      responseValidator = (IncomingResponseValidator<Object>) (_, _, requestMessage, response, input) -> {
        int c = response.httpMessage().statusCode();
        Status status;
        if (c >= 200 && c < 300) {
          status = Status.Ok;
        } else if (c >= 400 && c < 500) {
          status = Status.PermanentError;
        } else if (c >= 500 && c < 600) {
          status = Status.TransientError;
        } else {
          status = Status.PermanentError;
        }
        return Mono.just(new Result(
            status,
            c + " " + response.httpMessage().reasonPhrase(),
            null
        ));
      };
  }

  public static class Builder<T, U> {

    private Function<T, U> dataAdapter;
    private Class<? extends OutgoingRequestCreator<U>> notificationCreatorType;
    private OutgoingRequestCreator<U> notificationCreator;
    private Subscriber subscriber;
    private boolean guaranteed;
    private IncomingResponseValidator<?> responseValidator;

    public static <T> Builder<T, T> request(Class<? extends OutgoingRequestCreator<T>> notificationCreatorType) {
      Builder<T, T> builder = new Builder<>();
      builder.dataAdapter = Function.identity();
      builder.notificationCreatorType = notificationCreatorType;
      return builder;
    }

    public static <T, U> Builder<T, U> request(
        Function<T, U> dataAdapter,
        Class<? extends OutgoingRequestCreator<U>> notificationCreatorType
    ) {
      Builder<T, U> builder = new Builder<>();
      builder.dataAdapter = dataAdapter;
      builder.notificationCreatorType = notificationCreatorType;
      return builder;
    }

    public static <T> Builder<T, T> request(OutgoingRequestCreator<T> notificationCreator) {
      Builder<T, T> builder = new Builder<>();
      builder.notificationCreator = notificationCreator;
      return builder;
    }

    public Builder<T, U> to(Subscriber subscriber) {
      this.subscriber = subscriber;
      return this;
    }

    public Builder<T, U> guaranteed() {
      this.guaranteed = true;
      return this;
    }

    public Builder<T, U> responseValidator(IncomingResponseValidator<?> responseValidator) {
      this.responseValidator = responseValidator;
      return this;
    }

    public OutgoingRequestModel<T, U> build() {
      if (notificationCreator == null && notificationCreatorType == null)
        throw new IllegalArgumentException("Creator (type) not specified");
      if (notificationCreator != null && notificationCreatorType != null)
        throw new IllegalArgumentException("Both creator and creator type specified");
      return new OutgoingRequestModel<>(
          dataAdapter,
          notificationCreatorType,
          notificationCreator,
          subscriber,
          guaranteed,
          responseValidator
      );
    }


  }

}
