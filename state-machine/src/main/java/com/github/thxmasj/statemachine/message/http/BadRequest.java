package com.github.thxmasj.statemachine.message.http;

public class BadRequest extends HttpResponseCreator {

  public BadRequest() {
    super(400, "Bad Request");
  }

}
