package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.Validation.Invalid;
import com.github.thxmasj.statemachine.Validation.Valid;

public sealed interface Validation<T> permits Valid, Invalid {

  record Valid<T>(T value) implements Validation<T> {

    @Override
    public T valid() {
      return value;
    }

    @Override
    public String invalid() {
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

  record Invalid<T>(String reason) implements Validation<T> {

    @Override
    public T valid() {
      throw new IllegalStateException(reason);
    }

    @Override
    public String invalid() {
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

  T valid();

  String invalid();

  boolean isValid();

  boolean isInvalid();

}
