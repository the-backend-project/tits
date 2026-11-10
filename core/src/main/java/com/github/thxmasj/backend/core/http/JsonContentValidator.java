package com.github.thxmasj.backend.core.http;

import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.Validated.Invalid;
import com.github.thxmasj.backend.core.Validated.Valid;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.function.Function;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

public class JsonContentValidator<T> implements Function<byte[], Validated<T>> {

  private final Class<T> contentType;
  private final boolean validate;

  // Jackson 2 defaults keep validation as strict as before, e.g. failing on unknown properties
  private static final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults()
      .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
      .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(Include.NON_NULL).withContentInclusion(Include.NON_NULL))
      .build();

  private static final Validator jsonValidator = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory()
      .getValidator();

  public JsonContentValidator(Class<T> contentType, boolean validate) {
    this.contentType = contentType;
    this.validate = validate;
  }

  @Override
  public Validated<T> apply(byte[] content) {
    if (contentType == Void.class) {
      return new Valid<>(null);
    } else {
      try {
        T value = objectMapper.readValue(content, contentType);
        Set<ConstraintViolation<T>> violations = validate ? jsonValidator.validate(value) : Set.of();
        return violations.isEmpty() ?
            new Valid<>(value) :
            new Invalid<>(violations.stream()
                .map(v -> v == null ? "n/a" : v.getPropertyPath() + ": " + v.getMessage())
                .collect(joining(", ")));
      } catch (JacksonException e) {
        return new Invalid<>("Failed to parse content with " + contentType.getName() + ": " + e.getMessage());
      }
    }
  }

}
