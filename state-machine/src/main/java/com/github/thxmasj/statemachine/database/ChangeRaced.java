package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.database.mssql.ChangeState;

public class ChangeRaced extends RuntimeException {

  private final ChangeState.Change change;
  private final String tableName; // TODO: Use name of secondary id or "events" or ...

  public ChangeRaced(ChangeState.Change change, String tableName) {
    super(String.format("Change for event %s (%d) on %s raced on table %s",
        change.newEvent() != null ? change.newEvent().typeName() : "N/A",
        change.newEvent() != null ? change.newEvent().eventNumber() : -1,
        change.entityModel().name(),
        tableName
    ));
    this.change = change;
    this.tableName = tableName;
  }

  public ChangeState.Change change() {
    return change;
  }

  public String tableName() {
    return tableName;
  }
}
