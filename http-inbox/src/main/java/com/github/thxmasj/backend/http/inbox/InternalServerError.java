package com.github.thxmasj.backend.http.inbox;


public class InternalServerError extends HttpResponseCreator {

  public InternalServerError() {
    super(500, "Internal Server Error");
  }

}
