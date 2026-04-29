package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import java.util.List;

public class EventAlreadyExists extends RuntimeException {

  public EventAlreadyExists(String duplicateKey) {
    super("Event with raw id " + duplicateKey + " already exists");
  }

  public EventAlreadyExists(EntityId entityId, int eventNumber, List<Change> changes) {
    super("Event " + eventNumber + " on entity " + entityId.value() + " already exists. Changes:\n" + changes);
  }

}
