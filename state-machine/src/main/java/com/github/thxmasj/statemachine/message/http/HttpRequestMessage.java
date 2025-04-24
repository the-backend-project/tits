package com.github.thxmasj.statemachine.message.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpRequestMessage {

  private final String message;
  private final Method method;
  private final URI uri;
  private final Map<String, String> headers;
  private final String body;

  public String message() {
    return message;
  }

  public Method method() {
    return method;
  }

  public URI uri() {
    return uri;
  }

  public Map<String, String> headers() {
    return headers;
  }

  public String body() {
    return body;
  }

  public enum Method {GET, POST, PUT, DELETE}

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  public HttpRequestMessage(String message) {
    String requestLine = message.lines().findFirst().orElseThrow();
    Method method = Method.valueOf(requestLine.substring(0, requestLine.indexOf(" ")));
    URI uri = uri(message);
    Map<String, String> headers = Parser.headers(message);
    String body = Parser.body(message);
    this.message = message;
    this.method = method;
    this.uri = uri;
    this.headers = headers;
    this.body = body;
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

  public String headerValue(String header) {
    return headers.entrySet().stream()
        .filter(kv -> kv.getKey().equalsIgnoreCase(header))
        .map(Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  public <T> T body(Class<T> type) {
    if (body == null)
      return null;
    if (type == String.class)
      return type.cast(body);
    try {
      return objectMapper.readerFor(type).readValue(body);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private static URI uri(String message) {
    String startLine = message.lines().findFirst().orElseThrow();
    return URI.create(startLine.substring(message.indexOf(" ") + 1));
  }

}
