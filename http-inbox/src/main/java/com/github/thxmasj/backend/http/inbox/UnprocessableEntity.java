package com.github.thxmasj.backend.http.inbox;


class UnprocessableEntity extends HttpResponseCreator {

  public UnprocessableEntity() {
    super(422, "Unprocessable Entity");
  }

}
