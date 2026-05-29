package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.Validated.Invalid;
import com.github.thxmasj.statemachine.Validated.Valid;
import java.util.function.Predicate;

public sealed interface Validated<T> permits Valid, Invalid {

  static <T> Validated<T> validElse(T value, String invalidReason) {
    return value != null ? new Valid<>(value) : new Invalid<>(invalidReason);
  }

  static <T> Validated<T> valid(T value) {
    return new Valid<>(value);
  }

  static <T> Validated<T> invalid(String reason) {
    return new Invalid<>(reason);
  }

  record Valid<T>(T value) implements Validated<T> {

    @Override
    public T validValue() {
      return value;
    }

    @Override
    public String invalidReason() {
      throw new IllegalStateException("Not invalid");
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isInvalid() {
      return false;
    }
  }

  record Invalid<T>(String reason) implements Validated<T> {

    @Override
    public T validValue() {
      throw new IllegalStateException(reason);
    }

    @Override
    public String invalidReason() {
      return reason;
    }

    @Override
    public boolean isValid() {
      System.out.println("Not valid: " + reason);
      return false;
    }

    @Override
    public boolean isInvalid() {
      return true;
    }
  }

  T validValue();

  String invalidReason();

  boolean isValid();

  boolean isInvalid();

  default Validated<T> and(Predicate<T> predicate, String invalidReason) {
    if (isInvalid()) return this;
    return predicate.test(validValue()) ? this : invalid(invalidReason);
  }

}
