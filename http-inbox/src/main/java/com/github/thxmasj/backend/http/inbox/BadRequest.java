package com.github.thxmasj.backend.http.inbox;


class BadRequest extends HttpResponseCreator {

  public BadRequest() {
    super(400, "Bad Request");
  }

}
