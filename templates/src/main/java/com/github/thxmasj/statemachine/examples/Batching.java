package com.github.thxmasj.statemachine.examples;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Status;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.IncomingRequestModel.validator;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static com.github.thxmasj.statemachine.examples.Batching.EntityTypes.Item;
import static com.github.thxmasj.statemachine.examples.Batching.Events.AddToBatch;
import static com.github.thxmasj.statemachine.examples.Batching.Events.CreateItem;
import static com.github.thxmasj.statemachine.examples.Batching.States.Begin;

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

public class Batching {

  public enum EntityTypes implements EntityModel {
    Item,
    Batch
    ;

    @Override
    public List<EventType> eventTypes() {
      return List.of(Events.values());
    }

    @Override
    public State initialState() {
      return Begin;
    }

    @Override
    public List<TransitionModel<?>> transitions() {
      return List.of(
          from(Begin).to(Begin).onEvent(CreateItem)
              .response("Item created", new Created())
              .trigger(_ -> event(AddToBatch).onEntity(Batch).create()));
    }


  }

  public enum Events implements EventType {
    CreateItem(1),
    DeleteItem(2),
    AddToBatch(3),
    DeleteFromBatch(4),
    ;

    private final int id;

    Events(int id) {this.id = id;}

    @Override
    public int id() {
      return id;
    }
  }

  public enum States implements State {
    Begin
  }

  public class RequestMappings implements RequestMapper {

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
