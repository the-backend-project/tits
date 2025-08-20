package com.github.thxmasj.statemachine;

public class RequirementsNotFulfilled extends RuntimeException {

  public RequirementsNotFulfilled(String s) {
    super(s);
  }
}
