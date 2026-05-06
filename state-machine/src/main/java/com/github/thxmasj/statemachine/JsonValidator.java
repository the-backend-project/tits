package com.github.thxmasj.statemachine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.Validated.Invalid;
import com.github.thxmasj.statemachine.Validated.Valid;

public class JsonValidator<T> {

  private final ObjectReader objectReader;

  public JsonValidator(Class<T> type) {
    this.objectReader = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .readerFor(type);
  }

  public Validated<T> validate(String data) {
    try {
      return new Valid<>(objectReader.readValue(data));
    } catch (Exception e) {
      return new Invalid<>(e.getMessage());
    }
  }

}
