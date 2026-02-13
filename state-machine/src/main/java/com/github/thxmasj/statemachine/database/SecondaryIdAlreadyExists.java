package com.github.thxmasj.statemachine.database;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;

public class SecondaryIdAlreadyExists extends RuntimeException {

  private final String duplicateKey;
  private final String idTableName;
  private final SecondaryId secondaryId;
  private final Change change;

  public SecondaryIdAlreadyExists(Change change, SecondaryId secondaryId, String duplicateKey, String idTableName) {
    super("Secondary id " + secondaryId + " already exists (" + duplicateKey + ", " + idTableName + ")");
    System.out.println("Secondary id " + secondaryId + " already exists (" + duplicateKey + ", " + idTableName + ")");
    this.duplicateKey = duplicateKey;
    this.idTableName = idTableName;
    this.change = change;
    this.secondaryId = secondaryId;
  }

  public String duplicateKey() {
    return duplicateKey;
  }

  public String idTableName() {
    return idTableName;
  }

  public SecondaryId secondaryId() {
    return secondaryId;
  }

  public Change change() {
    return change;
  }

}
