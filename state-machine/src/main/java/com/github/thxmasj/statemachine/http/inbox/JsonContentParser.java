package com.github.thxmasj.statemachine.http.inbox;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.Validated.Invalid;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import java.util.Set;

import static java.util.stream.Collectors.joining;

public class JsonContentParser<T> implements ContentParser<T> {

  private final Class<T> contentType;
  private final boolean validate;

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  private static final Validator jsonValidator = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory()
      .getValidator();

  public JsonContentParser(Class<T> contentType, boolean validate) {
    this.contentType = contentType;
    this.validate = validate;
  }

  @Override
  public Validated<T> apply(HttpRequestMessage request) {
    if (contentType == Void.class) {
      return new Valid<>(null);
    } else {
      try {
        T value = objectMapper.readValue(request.body(), contentType);
        Set<ConstraintViolation<T>> violations = validate ? jsonValidator.validate(value) : Set.of();
        return violations.isEmpty() ?
            new Valid<>(value) :
            new Invalid<>(violations.stream()
                .map(v -> v == null ? "n/a" : v.getPropertyPath() + ": " + v.getMessage())
                .collect(joining(", ")));
      } catch (JsonProcessingException e) {
        return new Invalid<>("Failed to parse body with " + contentType.getName() + ": " + e.getMessage());
      }
    }
  }

}
