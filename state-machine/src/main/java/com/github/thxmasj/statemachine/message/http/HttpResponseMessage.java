package com.github.thxmasj.statemachine.message.http;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage.Deserializer;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage.Serializer;

import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.util.Map;

@JsonSerialize(using = Serializer.class)
@JsonDeserialize(using = Deserializer.class)
public class HttpResponseMessage {

  private final String message;
  private final int statusCode;
  private final String reasonPhrase;
  private final Map<String, String> headers;
  private final String body;

  public static class Serializer extends StdSerializer<HttpResponseMessage> {

    public Serializer() {
      super(HttpResponseMessage.class);
    }

    @Override
    public void serialize(HttpResponseMessage value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
      gen.writeString(value.message());
    }
  }

  public static class Deserializer extends StdDeserializer<HttpResponseMessage> {

    public Deserializer() {
      super(HttpResponseMessage.class);
    }

    @Override
    public HttpResponseMessage deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      return HttpMessageParser.parseResponse(p.getValueAsString());
    }
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase) {
    this(statusCode, reasonPhrase, Map.of());
  }

  public HttpResponseMessage(int statusCode, String reasonPhrase, Map<String, String> headers) {
    System.out.printf("Creating response without body: %d %s\n", statusCode, reasonPhrase);
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
    String m = String.format("HTTP/1.1 %d %s", statusCode, reasonPhrase);
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
