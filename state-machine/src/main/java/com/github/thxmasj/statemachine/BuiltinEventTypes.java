package com.github.thxmasj.statemachine;

public enum BuiltinEventTypes implements EventType {

  InvalidRequest(1), // Incoming request is not according to incoming requests model.
  RejectedRequest(2), // Incoming request is valid but not allowed for the entity's current state.
  FailedRequest(3), // Incoming request failed miserably (error not handled, bug).
  RequestUndelivered(4), // Outgoing request not delivered (f.ex. connection failure)
  InvalidResponse(5),
  Rollback(6) {
    @Override
    public boolean isRollback() {
      return true;
    }
  },
  InconsistentState(7),
  UnknownEntity(8) {
    @Override
    public boolean isReadOnly() {
      return true;
    }
  },
  Status(9) {
    @Override
    public boolean isReadOnly() {
      return true;
    }
  }
  ;

  private final int id;

  BuiltinEventTypes(int id) {this.id = id;}

  @Override
  public int id() {
    return id;
  }

}
