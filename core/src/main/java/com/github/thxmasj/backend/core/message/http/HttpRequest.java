package com.github.thxmasj.backend.core.message.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// An HTTP request, with a body of type `T`: `byte[]` for the request as sent or received, or the payload it is
/// parsed into or marshalled from, e.g. by [com.github.thxmasj.backend.core.http.HttpDataType]. A request without a
/// body has a null body. Requests are equal if their method, URI, headers and the content of their bodies are.
public record HttpRequest<T>(Method method, URI uri, Map<String, String> headers, T body) implements HttpMessage<T> {

  public enum Method {GET, POST, PUT, DELETE}

  public HttpRequest(Method method, URI uri) {
    this(method, uri, Map.of());
  }

  public HttpRequest(Method method, URI uri, Map<String, String> headers) {
    this(method, uri, headers, null);
  }

  public static HttpRequest<Void> create(Method method, URI uri) {
    return create(method, uri, Map.of());
  }

  public static HttpRequest<Void> create(Method method, URI uri, Map<String, String> headers) {
    return new HttpRequest<>(method, uri, headers, null);
  }

  public static <T> HttpRequest<T> create(Method method, URI uri, T body) {
    return create(method, uri, Map.of(), body);
  }

  public static <T> HttpRequest<T> create(Method method, URI uri, Map<String, String> headers, T body) {
    return new HttpRequest<>(method, uri, headers, body);
  }

  /// This request with the body mapped by the function, e.g. a payload marshalled into bytes
  public <U> HttpRequest<U> map(Function<? super T, ? extends U> mapper) {
    return new HttpRequest<>(method, uri, headers, mapper.apply(body));
  }

  public String requestLine() {
    return method.name() + " " + uri.toString();
  }

  public List<String> uriValues(Pattern pattern) {
    Matcher matcher = pattern.matcher(uri.toString());
    if (matcher.find()) {
      List<String> result = new ArrayList<>(matcher.groupCount());
      for (int i = 1; i <= matcher.groupCount(); i++) {
        result.add(matcher.group(i));
      }
      return result;
    }
    throw new IllegalArgumentException();
  }

  @Override
  public String startLine() {
    return requestLine();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof HttpRequest<?> that
        && method == that.method
        && Objects.equals(uri, that.uri)
        && Objects.equals(headers, that.headers)
        && Objects.deepEquals(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, uri, headers, Arrays.deepHashCode(new Object[] {body}));
  }

  @Override
  public String toString() {
    return HttpMessage.toString(this);
  }
}
