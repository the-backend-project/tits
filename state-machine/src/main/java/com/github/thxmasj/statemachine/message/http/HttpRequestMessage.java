package com.github.thxmasj.statemachine.message.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpRequestMessage extends HttpMessage {

  private final Method method;
  private final URI uri;

  public Method method() {
    return method;
  }

  public URI uri() {
    return uri;
  }

  public enum Method {GET, POST, PUT, DELETE}

  public HttpRequestMessage(Method method, URI uri) {
    this(method, uri, Map.of());
  }

  public HttpRequestMessage(Method method, URI uri, Map<String, String> headers) {
    this(method, uri, headers, null);
  }

  public HttpRequestMessage(Method method, URI uri, Map<String, String> headers, byte[] body) {
    super(headers, body);
    this.method = method;
    this.uri = uri;
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
  public String head() {
    return String.format("%s %s", method, uri.toString());
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof HttpRequestMessage that)) {
      return false;
    }
    return super.equals(that);
  }

}
