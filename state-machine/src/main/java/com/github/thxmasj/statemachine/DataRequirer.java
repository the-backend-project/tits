package com.github.thxmasj.statemachine;

public interface DataRequirer {

  default Requirements requirements() {
    return Requirements.none();
  }

}
