package com.github.thxmasj.statemachine.database;

public class EventAlreadyExists extends RuntimeException {

  private final String duplicateKey;

  public EventAlreadyExists(String duplicateKey) {
    super("Event with id " + duplicateKey + " already exists");
    this.duplicateKey = duplicateKey;
  }

  public String duplicateKey() {
    return duplicateKey;
  }
}
