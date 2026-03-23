package com.github.thxmasj.statemachine.message;

import static com.github.thxmasj.statemachine.message.JsonValidator.Status.Invalid;
import static com.github.thxmasj.statemachine.message.JsonValidator.Status.Valid;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

public class JsonValidator {

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @SuppressWarnings("resource")
  private final static Validator jsonValidator = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory()
      .getValidator();

  public enum Status {Valid, Invalid}
  public record Result<T>(Status status, String error, T value) {

    public boolean isValid() {
      return status == Valid;
    }

    public boolean isInvalid() {
      return status == Invalid;
    }

  }

  public static <T> Result<T> json(String jsonString, Class<T> targetType) {
    T target;
    try {
      target = objectMapper.readerFor(targetType).readValue(jsonString);
    } catch (JsonProcessingException e) {
      return new Result<>(Invalid, e.getMessage(), null);
    }
    var violations = jsonValidator.validate(target);
    if (!violations.isEmpty())
      return new Result<>(Invalid, new ConstraintViolationException(violations).getMessage(), null);
    return new Result<>(Valid, null, target);
  }

}
