package com.github.thxmasj.backend.core.message.http;

public class BadRequest extends HttpResponseCreator {

  public BadRequest() {
    super(400, "Bad Request");
  }

}
