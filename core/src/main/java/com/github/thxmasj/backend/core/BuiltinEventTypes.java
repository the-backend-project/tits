package com.github.thxmasj.backend.core;

import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.BasicEventType.Rollback;
import java.util.List;
import java.util.UUID;

public interface BuiltinEventTypes {

  EventType<Rollback.Data, Rollback.Data> EventsRolledBack = new Rollback(
      "Events rolled back",
      UUID.fromString("58aa1e1f-e75d-40ba-9e87-ca7fc42e491d")
  );
  EventType<Void, State> StatusLookedUp = new ReadOnly<>(
      "Status looked up",
      UUID.fromString("324dc75d-e83d-4b9b-8ad9-b3521184def6"),
      DataType.none(),
      DataType.json(State.class)
  );

  List<EventType<?, ?>> ALL = List.of(
      EventsRolledBack,
      StatusLookedUp
  );

}
