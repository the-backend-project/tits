package com.github.thxmasj.statemachine.templates;

import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.templates.Batching.States.Begin;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Batching {

  public enum EntityTypes implements EntityModel {
    Item {
      @Override
      public UUID id() {
        return UUID.fromString("33026afa-b541-400e-b3ca-fe3c00af9e23");
      }
    },
    Batch {
      @Override
      public UUID id() {
        return UUID.fromString("5bec6418-4615-4b1f-a394-41e033bfb32a");
      }
    }
    ;

    @Override
    public State initialState() {
      return Begin;
    }

    public Map<State, List<TransitionModel<?, ?>>> transitions() {
      return Map.of(
          Begin, List.of(
            onEvent(CreateItem).to(Begin)
              .trigger(AddToBatch).on(Batch).identifiedBy(entityId(UUID.randomUUID()))
              //.trigger(new Created()).with(_ -> "Item created")
              .output()
          )
      );
    }


  }

  static EventType<Void, Void>
    CreateItem = BasicEventType.of("CreateItem", UUID.fromString("b6c4ed96-4cfc-4258-a222-3a51064b35f7")),
    DeleteItem = BasicEventType.of("DeleteItem", UUID.fromString("485935f8-2f80-4228-8278-42e84e2d262d")),
    AddToBatch = BasicEventType.of("AddToBatch", UUID.fromString("ef054730-344c-4d1b-98a9-5aa7204a2eab")),
    DeleteFromBatch = BasicEventType.of("DeleteFromBatch", UUID.fromString("8dc15503-2a4d-491b-b258-82f209d32825"))
    ;

  public enum States implements State {
    Begin
  }

}
