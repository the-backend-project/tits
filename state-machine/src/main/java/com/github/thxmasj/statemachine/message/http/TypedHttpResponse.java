package com.github.thxmasj.statemachine.message.http;

import java.util.Map;

public record TypedHttpResponse<T>(int statusCode, String reasonPhrase, Map<String, String> headers, T payload) {

  public static TypedHttpResponse<Void> create(int statusCode, String reasonPhrase) {
    return create(statusCode, reasonPhrase, Map.of());
  }

  public static TypedHttpResponse<Void> create(int statusCode, String reasonPhrase, Map<String, String> headers) {
    return new TypedHttpResponse<>(statusCode, reasonPhrase, headers, null);
  }

  public static <T> TypedHttpResponse<T> create(int statusCode, String reasonPhrase, T payload) {
    return create(statusCode, reasonPhrase, Map.of(), payload);
  }

  public static <T> TypedHttpResponse<T> create(int statusCode, String reasonPhrase, Map<String, String> headers, T payload) {
    return new TypedHttpResponse<>(statusCode, reasonPhrase, headers, payload);
  }

}
