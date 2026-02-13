package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.Validation.Invalid;
import com.github.thxmasj.statemachine.Validation.Valid;

public sealed interface Validation<T> permits Valid, Invalid {

  record Valid<T>(T value) implements Validation<T> {

    @Override
    public T validated() {
      return value;
    }
  }

  record Invalid<T>(String reason) implements Validation<T> {

    @Override
    public T validated() {
      return null;
    }
  }

  T validated();

}
