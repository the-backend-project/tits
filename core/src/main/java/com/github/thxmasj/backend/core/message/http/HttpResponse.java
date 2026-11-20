package com.github.thxmasj.backend.core.message.http;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/// An HTTP response, with a body of type `T`: `byte[]` for the response as sent or received, or the payload it is
/// parsed into or marshalled from, e.g. by [com.github.thxmasj.backend.core.http.HttpDataType]. A response without a
/// body has a null body. Responses are equal if their status, their headers, with the names compared without case,
/// and the content of their bodies are.
public record HttpResponse<T>(int statusCode, String reasonPhrase, Map<String, String> headers, T body)
    implements HttpMessage<T> {

  public HttpResponse {
    Objects.requireNonNull(headers, "headers, which are Map.of() for none");
  }

  public HttpResponse(int statusCode, String reasonPhrase) {
    this(statusCode, reasonPhrase, Map.of());
  }

  public HttpResponse(int statusCode, String reasonPhrase, Map<String, String> headers) {
    this(statusCode, reasonPhrase, headers, null);
  }

  public static HttpResponse<Void> create(int statusCode, String reasonPhrase) {
    return create(statusCode, reasonPhrase, Map.of());
  }

  public static HttpResponse<Void> create(int statusCode, String reasonPhrase, Map<String, String> headers) {
    return new HttpResponse<>(statusCode, reasonPhrase, headers, null);
  }

  public static <T> HttpResponse<T> create(int statusCode, String reasonPhrase, T body) {
    return create(statusCode, reasonPhrase, Map.of(), body);
  }

  public static <T> HttpResponse<T> create(int statusCode, String reasonPhrase, Map<String, String> headers, T body) {
    return new HttpResponse<>(statusCode, reasonPhrase, headers, body);
  }

  /// This response with the body mapped by the function, e.g. bytes parsed into a payload
  public <U> HttpResponse<U> map(Function<? super T, ? extends U> mapper) {
    return new HttpResponse<>(statusCode, reasonPhrase, headers, mapper.apply(body));
  }

  public String statusLine() {
    return String.format("HTTP/1.1 %d %s", statusCode, reasonPhrase);
  }

  @Override
  public String startLine() {
    return statusLine();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof HttpResponse<?> that
        && statusCode == that.statusCode
        && Objects.equals(reasonPhrase, that.reasonPhrase)
        && HttpMessage.headersEqual(headers, that.headers)
        && HttpMessage.bodiesEqual(body, that.body);
  }

  @Override
  public int hashCode() {
    return Objects.hash(statusCode, reasonPhrase, HttpMessage.headersHashCode(headers), HttpMessage.bodyHashCode(body));
  }

  @Override
  public String toString() {
    return HttpMessage.toString(this);
  }
}
