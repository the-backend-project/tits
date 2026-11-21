package com.github.thxmasj.backend.http.inbox;


class Conflict extends HttpResponseCreator {

  public Conflict() {
    super(409, "Conflict");
  }

}
