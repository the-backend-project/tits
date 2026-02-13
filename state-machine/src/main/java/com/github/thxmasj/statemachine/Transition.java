package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import com.github.thxmasj.statemachine.message.Message.OutgoingResponse;
import com.github.thxmasj.statemachine.message.Message.IncomingRequest;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import java.time.ZonedDateTime;
import java.util.List;

public class Transition<I, T, P, O> implements Change {
  final ZonedDateTime timestamp;
  //final TransitionModel<I, T, P, O> model;
  final Entity entity;
  InputEvent<I> input;
  int eventNumber;
  String correlationId;
  ZonedDateTime deadline;
  MultiTransition<T> nestedTransition;
  P assembledData;
  List<SecondaryId>  secondaryIds;
  IncomingRequest incomingRequest;
  OutgoingResponse outgoingResponse;
  List<OutgoingRequest> outgoingRequests;
  IncomingResponse incomingResponse;
  Event<O> output;
  ProcessResult result;

  public Transition(
      ZonedDateTime timestamp,
      //TransitionModel<I, T, P, O> model,
      Entity entity) {
    this.timestamp = timestamp;
    //this.model = model;
    this.entity = entity;
  }

  @Override
  public EntityModel entityModel() {
    return entity.model();
  }

  @Override
  public EntityId entityId() {
    return entity.id();
  }

  @Override
  public Event<?> newEvent() {
    return output;
  }

  @Override
  public State toState() {
    return null;
    //return model.toState();
  }

  @Override
  public List<SecondaryId> newSecondaryIds() {
    return secondaryIds;
  }

  @Override
  public IncomingRequest incomingRequest() {
    return incomingRequest;
  }

//  @Override
//  public OutgoingResponse outgoingResponse() {
//    return outgoingResponse;
//  }

  @Override
  public List<OutgoingRequest> outgoingRequests() {
    return outgoingRequests;
  }

  @Override
  public IncomingResponse incomingResponse() {
    return incomingResponse;
  }

  @Override
  public ZonedDateTime deadline() {
    return deadline;
  }

  @Override
  public String correlationId() {
    return correlationId;
  }
}
