package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;

public interface EntityModel {

  String name();

  UUID id();

  default List<SecondaryIdModel<?>> secondaryIds() {
    return List.of();
  }

  State initialState();

  static EntityModel of(String name, UUID id, State initialState) {
    return new EntityModel() {
      @Override public String name() {return name;}
      @Override public UUID id() {return id;}
      @Override public State initialState() {return initialState;}
    };
  }
}
