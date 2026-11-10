package com.github.thxmasj.backend.core.message.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

public class HttpResponseCreator {

  private static final ObjectWriter jsonWriter = JsonMapper.builderWithJackson2Defaults()
      .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
      .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL).withContentInclusion(JsonInclude.Include.NON_NULL))
      .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
      .build()
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
    return jsonWriter.writeValueAsBytes(object);
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
