package com.github.thxmasj.backend.core.message.http;

public class UnprocessableEntity extends HttpResponseCreator {

  public UnprocessableEntity() {
    super(422, "Unprocessable Entity");
  }

}
