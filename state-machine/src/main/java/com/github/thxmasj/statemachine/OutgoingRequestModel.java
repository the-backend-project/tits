package com.github.thxmasj.statemachine;

import java.util.function.*;

public record OutgoingRequestModel<T, U>(
    Function<T, U> dataAdapter,
    Class<? extends OutgoingRequestCreator<U>> notificationCreatorType,
    OutgoingRequestCreator<U> notificationCreator,
    Subscriber subscriber,
    boolean guaranteed,
    IncomingResponseValidator<?> responseValidator
) {

  public static class Builder<T, U> {

    private Function<T, U> dataAdapter;
    private Class<? extends OutgoingRequestCreator<U>> notificationCreatorType;
    private OutgoingRequestCreator<U> notificationCreator;
    private Subscriber subscriber;
    private boolean guaranteed;
    private IncomingResponseValidator<?> responseValidator;

    public static <T> Builder<T, T> notify(Subscriber subscriber) {
      Builder<T, T> builder = new Builder<>();
      builder.subscriber = subscriber;
      return builder;
    }

    public static <T> Builder<T, T> creator(Class<? extends OutgoingRequestCreator<T>> notificationCreatorType) {
      Builder<T, T> builder = new Builder<>();
      builder.dataAdapter = Function.identity();
      builder.notificationCreatorType = notificationCreatorType;
      return builder;
    }

    public static <T, U> Builder<T, U> creator(
        Function<T, U> dataAdapter,
        Class<? extends OutgoingRequestCreator<U>> notificationCreatorType
    ) {
      Builder<T, U> builder = new Builder<>();
      builder.dataAdapter = dataAdapter;
      builder.notificationCreatorType = notificationCreatorType;
      return builder;
    }

    public static <T> Builder<T, T> send(OutgoingRequestCreator<T> notificationCreator) {
      Builder<T, T> builder = new Builder<>();
      builder.notificationCreator = notificationCreator;
      return builder;
    }

    public static <T> Builder<T, T> response(OutgoingRequestCreator<T> notificationCreator) {
      Builder<T, T> builder = new Builder<>();
      builder.notificationCreator = notificationCreator;
      builder.to(null);
      return builder;
    }

    public static <T, U> Builder<T, U> builder() {
      return new Builder<>();
    }

    public Builder<T, U> subscriber(Subscriber subscriber) {
      this.subscriber = subscriber;
      return this;
    }

    public Builder<T, U> to(Subscriber subscriber) {
      this.subscriber = subscriber;
      return this;
    }

    public Builder<T, U> with(Class<? extends OutgoingRequestCreator<U>> notificationCreatorType) {
      this.notificationCreatorType = notificationCreatorType;
      return this;
    }

    public Builder<T, U> with(OutgoingRequestCreator<U> notificationCreator) {
      this.notificationCreator = notificationCreator;
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
