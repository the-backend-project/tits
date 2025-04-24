package com.github.thxmasj.statemachine;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import reactor.core.publisher.Mono;

public abstract class IncomingResponseJsonValidator<INPUT_TYPE, OUTPUT_TYPE>
    implements IncomingResponseValidator<OUTPUT_TYPE> {

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private final Class<INPUT_TYPE> inputType;
  private final Validator jsonValidator;

  public IncomingResponseJsonValidator(Class<INPUT_TYPE> inputType) {
    this.inputType = inputType;
    this.jsonValidator = Validation.byDefaultProvider()
        .configure()
        .messageInterpolator(new ParameterMessageInterpolator())
        .buildValidatorFactory()
        .getValidator();
  }

  public final Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingResponse response,
      Input input
  ) {
    if (response.httpMessage().statusCode() < 200 || response.httpMessage().statusCode() >= 300)
      return execute(entityId, context, response, input, null);
    else if (response.httpMessage().body() == null)
      return Mono.just(context.rollback("Missing response message"));
    INPUT_TYPE jsonBody;
    try {
      jsonBody = objectMapper.readerFor(inputType).readValue(response.httpMessage().body());
    } catch (JsonProcessingException e) {
      return Mono.just(context.rollback("Failed to parse JSON response message: " + e.getMessage()));
    }
    var violations = jsonValidator.validate(jsonBody);
    if (!violations.isEmpty())
      return Mono.just(context.rollback(new ConstraintViolationException(violations).getMessage()));
    return execute(entityId, context, response, input, jsonBody);
  }

  public abstract Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingResponse response,
      Input input,
      INPUT_TYPE jsonBody
  );

  public static <T> IncomingResponseJsonValidator<T, T> jsonResponse(Class<T> dataType, EventType eventType) {
    return new IncomingResponseJsonValidator<>(dataType) {
      @Override
      public Mono<Event> execute(
          EntityId entityId,
          Context<T> context,
          Input.IncomingResponse response,
          Input input,
          T jsonBody
      ) {
        return Mono.just(context.validResponse(eventType, jsonBody));
      }
    };
  }

}
