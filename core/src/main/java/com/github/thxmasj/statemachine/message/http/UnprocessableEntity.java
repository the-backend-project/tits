package com.github.thxmasj.statemachine.message.http;

public class UnprocessableEntity extends HttpResponseCreator {

  public UnprocessableEntity() {
    super(422, "Unprocessable Entity");
  }

}
