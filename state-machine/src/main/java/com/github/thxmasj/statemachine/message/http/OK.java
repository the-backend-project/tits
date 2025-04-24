package com.github.thxmasj.statemachine.message.http;

public class OK extends HttpResponseCreator {

  public OK() {
    super(200, "OK");
  }

}
