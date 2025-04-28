package com.github.thxmasj.statemachine.examples;

import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.IncomingRequestModel.validator;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static com.github.thxmasj.statemachine.examples.RequestReply.Events.Exchange;
import static com.github.thxmasj.statemachine.examples.RequestReply.States.Begin;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingRequestModelBuilder;
import com.github.thxmasj.statemachine.IncomingRequestValidator;
import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.http.RequestMapper;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.io.IOException;
import java.util.List;

public class RequestReply implements EntityModel {

  public static final EntityModel INSTANCE = new RequestReply();

  public enum Events implements EventType {
    Exchange(1);

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
        case String l when l.matches("GET .*/payments/[0-9a-f-]{36}/status") ->
            validator(new IncomingRequestValidator<Void>() {})
                .trigger(event(Exchange).onEntity(INSTANCE).create())
                .clientId("internal")
                .derivedMessageId();
        default -> null;
      };
    }

  }

  @Override
  public String name() {
    return getClass().getSimpleName();
  }

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
        from(Begin).to(Begin).onEvent(Exchange).response("Hello, World!", new Created())
    );
  }

}
