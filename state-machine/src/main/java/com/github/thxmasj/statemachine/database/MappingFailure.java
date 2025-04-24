package com.github.thxmasj.statemachine.database;

public class MappingFailure extends RuntimeException {

  public MappingFailure(Throwable cause) {
    super(cause);
  }

  public MappingFailure(String message) {
    super(message);
  }

}
