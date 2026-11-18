package com.github.thxmasj.backend.http.inbox;


public class UnprocessableEntity extends HttpResponseCreator {

  public UnprocessableEntity() {
    super(422, "Unprocessable Entity");
  }

}
