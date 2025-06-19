package com.github.thxmasj.statemachine;

import java.util.UUID;

public enum BuiltinEventTypes implements EventType {

  InvalidRequest(UUID.fromString("23d52456-e8b7-4409-aa9d-0998ef903471")), // Incoming request is not according to incoming requests model.
  RejectedRequest(UUID.fromString("21318498-78a3-4f81-97dc-07bb1467c455")), // Incoming request is valid but not allowed for the entity's current state.
  FailedRequest(UUID.fromString("de1feadd-8023-4581-acab-d629d174e523")), // Incoming request failed miserably (error not handled, bug).
  RequestUndelivered(UUID.fromString("98ef4100-34e8-426b-9fb8-539626821537")), // Outgoing request not delivered (f.ex. connection failure)
  InvalidResponse(UUID.fromString("450679ab-bc60-46cb-bb97-d171c34c2750")),
  Rollback(UUID.fromString("58aa1e1f-e75d-40ba-9e87-ca7fc42e491d")) {
    @Override
    public boolean isRollback() {
      return true;
    }
  },
  InconsistentState(UUID.fromString("7d4792e3-35b4-471f-9619-cac7051fa45c")),
  UnknownEntity(UUID.fromString("2ffed3fc-3efd-404c-9b11-f5a99fb47a5f")) {
    @Override
    public boolean isReadOnly() {
      return true;
    }
  },
  Status(UUID.fromString("324dc75d-e83d-4b9b-8ad9-b3521184def6")) {
    @Override
    public boolean isReadOnly() {
      return true;
    }
  }
  ;

  private final UUID id;

  BuiltinEventTypes(UUID id) {this.id = id;}

  @Override
  public UUID id() {
    return id;
  }

}
