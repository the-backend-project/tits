package com.github.thxmasj.backend.core.message.http;

public class InternalServerError extends HttpResponseCreator {

  public InternalServerError() {
    super(500, "Internal Server Error");
  }

}
