package com.github.thxmasj.backend.core.database;

public class MappingFailure extends RuntimeException {

  public MappingFailure(Throwable cause) {
    super(cause);
  }

  public MappingFailure(String message) {
    super(message);
  }

  public MappingFailure(String message, Throwable cause) {
    super(message, cause);
  }

}
