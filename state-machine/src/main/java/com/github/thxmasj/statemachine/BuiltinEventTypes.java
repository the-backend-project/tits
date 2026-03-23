package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BasicEventType.of;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType.ReadOnly;
import com.github.thxmasj.statemachine.BasicEventType.Rollback;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import java.util.List;
import java.util.UUID;

public interface BuiltinEventTypes {

  EventType<String, String>
    InconsistentState = of("InconsistentState", UUID.fromString("7d4792e3-35b4-471f-9619-cac7051fa45c"), String.class),
    InvalidResponse = of("InvalidResponse", UUID.fromString("450679ab-bc60-46cb-bb97-d171c34c2750"), String.class);
  EventType<String, Void>
    // Incoming request is valid but not allowed for the entity's current state.
    //RejectedRequest = of("RejectedRequest", UUID.fromString("21318498-78a3-4f81-97dc-07bb1467c455"), String.class, Void.class),
    // Incoming request is not according to incoming requests model.
    //InvalidRequest = of("InvalidRequest", UUID.fromString("23d52456-e8b7-4409-aa9d-0998ef903471"), String.class, Void.class),
    // Outgoing request not delivered (f.ex. connection failure)
    RequestUndelivered = of("RequestUndelivered", UUID.fromString("98ef4100-34e8-426b-9fb8-539626821537"), String.class, Void.class);
  EventType<Rollback.Data, Rollback.Data>
    Rollback = new Rollback("Rollback", UUID.fromString("58aa1e1f-e75d-40ba-9e87-ca7fc42e491d"));
  EventType<Void, Void>
    // Incoming request failed miserably (error not handled, bug).
    FailedRequest = of("FailedRequest", UUID.fromString("de1feadd-8023-4581-acab-d629d174e523")),
    UnknownEntity = new ReadOnly<>("UnknownEntity", UUID.fromString("2ffed3fc-3efd-404c-9b11-f5a99fb47a5f"), Void.class, Void.class);
  EventType<Void, State> Status = new ReadOnly<>("Status", UUID.fromString("324dc75d-e83d-4b9b-8ad9-b3521184def6"), Void.class, State.class);
  EventType<Tuple3<Change, SecondaryId<?>, EventLog>, Void>
      SecondaryIdAlreadyExists = of("SecondaryIdAlreadyExists", UUID.fromString("d94ce75c-cb6a-41cd-a652-a210e6de1516"), new DataType<>(new TypeReference<>() {}, Change.class, SecondaryId.class, EventLog.class), Void.class);

  List<EventType<?, ?>> ALL = List.of(
      //InvalidRequest,
      //RejectedRequest,
      FailedRequest,
      RequestUndelivered,
      InvalidResponse,
      Rollback,
      InconsistentState,
      UnknownEntity,
      Status,
      SecondaryIdAlreadyExists
  );

}
