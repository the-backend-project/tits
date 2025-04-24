package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public class IncomingRequestModelBuilder<DATA_TYPE> {

  private boolean matches;
  private String messageId;
  private boolean derivedMessageId;
  private String clientId;
  private String correlationId;
  private EventTriggerBuilder eventTrigger;
  private Class<? extends IncomingRequestValidator<?>> validatorClass;
  private IncomingRequestValidator<?> validator;
  private byte[] digest;

  public IncomingRequestModelBuilder<DATA_TYPE> matches(boolean matches) {
    this.matches = matches;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> trigger(EventTriggerBuilder eventTrigger) {
    this.eventTrigger = eventTrigger;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> messageId(String messageId) {
    this.messageId = messageId;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> derivedMessageId() {
    this.derivedMessageId = true;
    return this;
  }

  public static String fromRequestLine(HttpRequestMessage message, String pattern, int captureGroup) {
    Matcher matcher = Pattern.compile(pattern).matcher(message.requestLine());
    return matcher.find() ? matcher.group(captureGroup) : null;
  }

  public static <T> T fromRequestLine(HttpRequestMessage message, String pattern, Function<Matcher, T> idBuilder) {
    Matcher matcher = Pattern.compile(pattern).matcher(message.requestLine());
    return matcher.find() ? idBuilder.apply(matcher) : null;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> clientId(String clientId) {
    this.clientId = clientId;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> correlationId(String correlationId) {
    this.correlationId = correlationId;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> validator(Class<? extends IncomingRequestValidator<?>> validatorClass) {
    this.validatorClass = validatorClass;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> validator(IncomingRequestValidator<?> validator) {
    this.validator = validator;
    return this;
  }

  public IncomingRequestModelBuilder<DATA_TYPE> digest(byte[] digest) {
    this.digest = digest;
    return this;
  }

  public IncomingRequestModel build() {
    if (eventTrigger == null)
      throw new IllegalArgumentException("eventTrigger not set");
    if (clientId == null)
      throw new IllegalArgumentException("clientId not set");
    //noinspection SimplifiableBooleanExpression
    if (!(messageId != null ^ derivedMessageId))
      throw new IllegalArgumentException("messageId xor derivedMessageId must be set");
    //noinspection SimplifiableBooleanExpression
    if (!(validatorClass != null ^ validator != null))
      throw new IllegalArgumentException("validatorClass xor validator must be set");

    return new IncomingRequestModel(
        matches,
        eventTrigger,
        messageId,
        derivedMessageId,
        clientId,
        correlationId,
        validatorClass,
        validator,
        digest
    );
  }

}
