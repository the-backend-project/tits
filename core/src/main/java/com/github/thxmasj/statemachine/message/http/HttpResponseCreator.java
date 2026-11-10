package com.github.thxmasj.statemachine.message.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class HttpResponseCreator {

  private static final ObjectWriter jsonWriter = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .writer();
  private final int statusCode;
  private final String reasonPhrase;

  public HttpResponseCreator(int statusCode, String reasonPhrase) {
    this.statusCode = statusCode;
    this.reasonPhrase = reasonPhrase;
  }

  public HttpResponseMessage create(
      String data,
      UUID entityId,
      ZonedDateTime timestamp,
      String correlationId
  ) {
    return new HttpResponseMessage(
        statusCode,
        reasonPhrase,
        headers(correlationId, data),
        json(body(entityId, data, timestamp))
    );
  }

  private byte[] json(Map<String, Object> object) {
    try {
      return jsonWriter.writeValueAsBytes(object);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  protected Map<String, String> headers(String correlationId, String data) {
    return Map.of(
        "Content-Type", "application/json;charset=UTF-8",
        "X-Correlation-Id", correlationId
    );
  }

  protected Map<String, Object> body(UUID entityId, String detail, ZonedDateTime timestamp) {
    var map = new LinkedHashMap<String, Object>(5);
    map.put("type", "about:blank");
    map.put("title", reasonPhrase);
    map.put("status", statusCode);
    map.put("detail", detail);
    if (entityId != null) map.put("entityId", entityId);
    map.put("timestamp", timestamp);
    return map;
  }

}
