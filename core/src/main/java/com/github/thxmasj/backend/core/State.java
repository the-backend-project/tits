package com.github.thxmasj.backend.core;

public interface State {

  State Intermediate = State.of("Intermediate");

  static State of(String name) {
    return () -> name;
  }

  String name();

}
