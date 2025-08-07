package com.github.thxmasj.statemachine.message.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.message.Message.IncomingRequest;
import com.github.thxmasj.statemachine.OutgoingResponseCreator;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

public class HttpResponseCreator implements OutgoingResponseCreator<String> {

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

  @Override
  public Mono<HttpResponseMessage> create(
      String data,
      IncomingRequest incomingRequest,
      EntityId entityId,
      String correlationId,
      Input input
  ) {
    return Mono.just(
        new HttpResponseMessage(
            statusCode,
            reasonPhrase,
            headers(correlationId, data),
            json(body(entityId, data, input.timestamp()))
        )
    );
  }

  private String json(Map<String, Object> object) {
    try {
      return jsonWriter.writeValueAsString(object);
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

  protected Map<String, Object> body(EntityId entityId, String detail, ZonedDateTime timestamp) {
    var map = new LinkedHashMap<String, Object>(5);
    map.put("type", "about:blank");
    map.put("title", reasonPhrase);
    map.put("status", statusCode);
    map.put("detail", detail);
    map.put("entityId", entityId.value());
    map.put("timestamp", timestamp);
    return map;
  }

}
