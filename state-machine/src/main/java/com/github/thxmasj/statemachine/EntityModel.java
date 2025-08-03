package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;
import java.util.UUID;

public interface EntityModel {

  String name();

  UUID id();

  default List<SecondaryIdModel> secondaryIds() {
    return List.of();
  }

  State initialState();

  List<TransitionModel<?, ?>> transitions();

  default List<OutboxQueue> queues() {
    return List.of();
  }

  default EntityModel parentEntity() {
    return null;
  }

}
