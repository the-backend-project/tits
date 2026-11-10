package com.github.thxmasj.statemachine.database;

public class DuplicateMessage extends RuntimeException {

  public DuplicateMessage(Throwable cause) {
    super(cause);
  }

}
