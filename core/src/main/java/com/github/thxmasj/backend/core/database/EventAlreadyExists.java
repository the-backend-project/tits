package com.github.thxmasj.backend.core.database;

import com.github.thxmasj.backend.core.database.ChangeState.Change;
import java.util.List;
import java.util.UUID;

public class EventAlreadyExists extends RuntimeException {

  public EventAlreadyExists(String duplicateKey) {
    super("Event with raw id " + duplicateKey + " already exists");
  }

  public EventAlreadyExists(UUID entityId, int eventNumber, List<Change> changes) {
    super("Event " + eventNumber + " on entity " + entityId + " already exists. Changes:\n" + changes);
  }

}
