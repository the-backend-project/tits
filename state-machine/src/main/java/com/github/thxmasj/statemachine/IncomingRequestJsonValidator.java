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


public class IncomingRequestJsonValidator<INPUT_TYPE, OUTPUT_TYPE>
    implements IncomingRequestValidator<OUTPUT_TYPE> {

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(JsonInclude.Include.NON_NULL);
  private final Class<INPUT_TYPE> inputType;
  private final Validator jsonValidator = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory()
      .getValidator();

  public IncomingRequestJsonValidator(Class<INPUT_TYPE> inputType) {this.inputType = inputType;}

  @Override
  public final Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingRequest request,
      Input input
  ) {
    INPUT_TYPE jsonBody;
    try {
      jsonBody = objectMapper.readerFor(inputType).readValue(request.httpMessage().body());
    } catch (JsonProcessingException e) {
      return Mono.just(context.invalidRequest(e.getMessage()));
    }
    var violations = jsonValidator.validate(jsonBody);
    if (!violations.isEmpty())
      return Mono.just(context.invalidRequest(new ConstraintViolationException(violations).getMessage()));
    return execute(entityId, context, request, input, jsonBody);
  }

  public Mono<Event> execute(
      EntityId entityId,
      Context<OUTPUT_TYPE> context,
      Input.IncomingRequest request,
      Input input,
      INPUT_TYPE jsonBody
  ) {
    return Mono.just(context.validRequest());
  }

}
