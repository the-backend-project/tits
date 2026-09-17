package com.github.thxmasj.statemachine.message.http;

import java.util.Map;

public final class HttpResponseMessage extends HttpMessage {

  private final int statusCode;
  private final String reasonPhrase;

  public HttpResponseMessage(int statusCode, String reasonPhrase) {
    this(statusCode, reasonPhrase, Map.of());
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, Map<String, String> headers) {
    this(statusCode, reasonPhrase, headers, null);
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, byte[] body) {
    this(statusCode, reasonPhrase, Map.of(), body);
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, Map<String, String> headers, byte[] body) {
    super(headers, body);
    this.statusCode = statusCode;
    this.reasonPhrase = reasonPhrase;
  }

  public int statusCode() {
    return statusCode;
  }

  public String statusLine() {
    return String.format("HTTP/1.1 %d %s", statusCode, reasonPhrase);
  }

  public String reasonPhrase() {
    return reasonPhrase;
  }

  @Override
  public String head() {
    return String.format("HTTP/1.1 %d %s", statusCode, reasonPhrase);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof HttpResponseMessage that))
      return false;
    return super.equals(that);
  }

}
