package com.github.thxmasj.statemachine.message.http;

import static java.util.stream.Collectors.joining;

import java.util.Map;

public class HttpResponseMessage {

  private final String message;
  private final int statusCode;
  private final String reasonPhrase;
  private final Map<String, String> headers;
  private final String body;

  public HttpResponseMessage(int statusCode, String reasonPhrase) {
    this(statusCode, reasonPhrase, Map.of());
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, Map<String, String> headers) {
    this(statusCode, reasonPhrase, headers, null);
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, String body) {
    this(statusCode, reasonPhrase, Map.of(), body);
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, Map<String, String> headers, String body) {
    this.statusCode = statusCode;
    this.reasonPhrase = reasonPhrase;
    this.headers = headers;
    this.body = body;
    this.message = String.format(
        """
        HTTP/1.1 %d %s
        %s
        %s
        """,
        statusCode,
        reasonPhrase,
        headers.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .collect(joining("\n")),
        (body == null ? "" : "\n" + body)
    );
  }

  public String message() {
    return message;
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

  public Map<String, String> headers() {
    return headers;
  }

  public String body() {
    return body;
  }

  @Override
  public String toString() {
    return message;
  }

}
