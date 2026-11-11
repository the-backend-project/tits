package com.github.thxmasj.backend.core.database;

public class DuplicateMessage extends RuntimeException {

  public DuplicateMessage(Throwable cause) {
    super(cause);
  }

}
