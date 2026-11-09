package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.BasicEventType.ReadOnly;
import com.github.thxmasj.statemachine.BasicEventType.Rollback;
import java.util.List;
import java.util.UUID;

public interface BuiltinEventTypes {

  EventType<Rollback.Data, Rollback.Data> Rollback = new Rollback(
      "Rollback",
      UUID.fromString("58aa1e1f-e75d-40ba-9e87-ca7fc42e491d")
  );
  EventType<Void, State> Status = new ReadOnly<>(
      "Status",
      UUID.fromString("324dc75d-e83d-4b9b-8ad9-b3521184def6"),
      DataType.none(),
      DataType.json(State.class)
  );

  List<EventType<?, ?>> ALL = List.of(
      Rollback,
      Status
  );

}
