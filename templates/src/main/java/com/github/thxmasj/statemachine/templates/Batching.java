package com.github.thxmasj.statemachine.templates;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Status;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.IncomingRequestModel.validator;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.onEvent;
import static com.github.thxmasj.statemachine.templates.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.templates.Batching.States.Begin;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingRequestModelBuilder;
import com.github.thxmasj.statemachine.IncomingRequestValidator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.http.RequestMapper;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.List;
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

    @Override
    public List<TransitionModel<?, ?>> transitions() {
      return List.of(
          onEvent(CreateItem).from(Begin).to(Begin)
              .response("Item created", new Created())
              .trigger(_ -> event(AddToBatch).onEntity(Batch).create()));
    }


  }

  static EventType<Void, Void>
    CreateItem = EventType.of("CreateItem", UUID.fromString("b6c4ed96-4cfc-4258-a222-3a51064b35f7")),
    DeleteItem = EventType.of("DeleteItem", UUID.fromString("485935f8-2f80-4228-8278-42e84e2d262d")),
    AddToBatch = EventType.of("AddToBatch", UUID.fromString("ef054730-344c-4d1b-98a9-5aa7204a2eab")),
    DeleteFromBatch = EventType.of("DeleteFromBatch", UUID.fromString("8dc15503-2a4d-491b-b258-82f209d32825"))
    ;

  public enum States implements State {
    Begin
  }

  public static class RequestMappings implements RequestMapper {

    @Override
    public IncomingRequestModelBuilder<?> incomingRequest(HttpRequestMessage message) {
      return switch (message.requestLine()) {
        case String l when l.matches("GET .*/items/[0-9a-f-]{36}/status") ->
            validator(new IncomingRequestValidator<Void>() {})
                .trigger(event(Status).onEntity(Item).create())
                .clientId("internal")
                .derivedMessageId();
        default -> null;
      };
    }

  }

}
