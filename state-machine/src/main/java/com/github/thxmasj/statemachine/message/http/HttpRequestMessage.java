package com.github.thxmasj.statemachine.message.http;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Deserializer;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Serializer;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@JsonSerialize(using = Serializer.class)
@JsonDeserialize(using = Deserializer.class)
public class HttpRequestMessage {

  private final Method method;
  private final URI uri;
  private final Map<String, String> headers;
  private final String body;
  private final String message;

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

  public static class Serializer extends StdSerializer<HttpRequestMessage> {

    public Serializer() {
      super(HttpRequestMessage.class);
    }

    @Override
    public void serialize(HttpRequestMessage value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
      gen.writeString(value.message());
    }
  }

  public static class Deserializer extends StdDeserializer<HttpRequestMessage> {

    public Deserializer() {
      super(HttpRequestMessage.class);
    }

    @Override
    public HttpRequestMessage deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      return HttpMessageParser.parseRequest(p.getValueAsString());
    }
  }

  public HttpRequestMessage(Method method, URI uri) {
    this(method, uri, Map.of());
  }

  public HttpRequestMessage(Method method, URI uri, Map<String, String> headers) {
    this(method, uri, headers, null);
  }

  public HttpRequestMessage(Method method, URI uri, Map<String, String> headers, String body) {
    this.method = method;
    this.uri = uri;
    this.headers = headers;
    this.body = body;
    var m = String.format("%s %s", method, uri.toString());
    if (!headers.isEmpty()) {
      m = m + "\n" + headers.entrySet().stream()
          .map(entry -> entry.getKey() + ":" + entry.getValue())
          .collect(joining("\n"));
    }
    if (body != null) {
      m = m + "\n\n" + body;
    }
    this.message = m;
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

  public HttpRequestMessage withoutHeader(String header) {
    if (!headers.containsKey(header))
      return this;
    return new HttpRequestMessage(
        method,
        uri,
        headers.entrySet().stream()
            .filter(e -> !e.getKey().equalsIgnoreCase(header))
            .collect(toMap(Entry::getKey, Entry::getValue)),
        body
    );
  }

  @Override
  public String toString() {
    return message;
  }

}
