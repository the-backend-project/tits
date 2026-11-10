package com.github.thxmasj.backend.core.message.http;

import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import java.net.URI;
import java.util.Map;

public record TypedHttpRequest<T>(Method method, URI uri, Map<String, String> headers, T payload) {

  public static TypedHttpRequest<Void> create(Method method, URI uri) {
    return create(method, uri, Map.of());
  }

  public static TypedHttpRequest<Void> create(Method method, URI uri, Map<String, String> headers) {
    return new TypedHttpRequest<>(method, uri, headers, null);
  }

  public static <T> TypedHttpRequest<T> create(Method method, URI uri, T payload) {
    return create(method, uri, Map.of(), payload);
  }

  public static <T> TypedHttpRequest<T> create(Method method, URI uri, Map<String, String> headers, T payload) {
    return new TypedHttpRequest<>(method, uri, headers, payload);
  }

}
