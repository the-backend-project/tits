package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("unused")
public class IncomingRequestModelBuilder<T> {

  private boolean matches;
  private String messageId;
  private boolean derivedMessageId;
  private String clientId;
  private String correlationId;
  private EventTrigger<T, ?, ?> eventTrigger;
  private Class<? extends IncomingRequestValidator<T>> validatorClass;
  private IncomingRequestValidator<T> validator;
  private byte[] digest;

  public static <T> IncomingRequestModelBuilder<T> validator(Class<? extends IncomingRequestValidator<T>> validator) {
    return new IncomingRequestModelBuilder<T>().withValidator(validator);
  }

  public static <T>IncomingRequestModelBuilder<T> validator(IncomingRequestValidator<T> validator) {
    return new IncomingRequestModelBuilder<T>().withValidator(validator);
  }

  public IncomingRequestModelBuilder<T> matches(boolean matches) {
    this.matches = matches;
    return this;
  }

  public <I, O> WithEventType<T, I, O> trigger(EventType<I, O> eventType) {
    return new WithEventType<>(this, eventType);
  }

  public record WithEventType<T, I1, O1>(IncomingRequestModelBuilder<T> builder, EventType<I1, O1> eventType) {

    public record WithEventTypeAndData<T, I1, O1>(WithEventType<T, I1, O1> eventType, Function<T, I1> dataAdapter) {
      public WithEntity<T, I1, O1> on(EntityModel entityModel) {
        return new WithEntity<>(this, entityModel);
      }
    }

    public record WithIdentifier<T, I1, O1>(WithEntity<T, I1, O1> entity, ArrayList<EntitySelector> entitySelectors) {}

    public record WithEntity<T, I1, O1>(WithEventTypeAndData<T, I1, O1> eventTypeAndData, EntityModel entityModel) {
      public IncomingRequestModelBuilder<T> identifiedBy(EntitySelector entitySelector) {
        eventTypeAndData.eventType.builder.eventTrigger = new EventTrigger<>(
            new EventSpec<>(eventTypeAndData.eventType.eventType, eventTypeAndData.dataAdapter),
            List.of(_ -> entitySelector),
            entityModel,
            false
        );
        return eventTypeAndData.eventType.builder;
      }
    }

    public WithEventTypeAndData<T, I1, O1> with(Function<T, I1> dataAdapter) {
      return new WithEventTypeAndData<>(this, dataAdapter);
    }

    public WithEntity<T, I1, O1> on(EntityModel entityModel) {
      return new WithEntity<>(new WithEventTypeAndData<>(this, _ -> null), entityModel);
    }

  }

  public IncomingRequestModelBuilder<T> messageId(String messageId) {
    this.messageId = requireNonNull(messageId, "messageId");
    return this;
  }

  public IncomingRequestModelBuilder<T> derivedMessageId() {
    this.derivedMessageId = true;
    return this;
  }

  public static String fromRequestLine(HttpRequestMessage message, String pattern, int captureGroup) {
    return fromRequestLine(message, Pattern.compile(pattern), captureGroup);
  }

  public static String fromRequestLine(HttpRequestMessage message, Pattern pattern, int captureGroup) {
    Matcher matcher = pattern.matcher(message.requestLine());
    return matcher.find() ? matcher.group(captureGroup) : null;
  }

  public static <T> T fromRequestLine(HttpRequestMessage message, String pattern, Function<Matcher, T> idBuilder) {
    return fromRequestLine(message, Pattern.compile(pattern), idBuilder);
  }

  public static <T> T fromRequestLine(HttpRequestMessage message, Pattern pattern, Function<Matcher, T> idBuilder) {
    Matcher matcher = pattern.matcher(message.requestLine());
    return matcher.find() ? idBuilder.apply(matcher) : null;
  }

  public IncomingRequestModelBuilder<T> clientId(String clientId) {
    this.clientId = clientId;
    return this;
  }

  public IncomingRequestModelBuilder<T> correlationId(String correlationId) {
    this.correlationId = correlationId;
    return this;
  }

  private IncomingRequestModelBuilder<T> withValidator(Class<? extends IncomingRequestValidator<T>> validatorClass) {
    this.validatorClass = validatorClass;
    return this;
  }

  private IncomingRequestModelBuilder<T> withValidator(IncomingRequestValidator<T> validator) {
    this.validator = validator;
    return this;
  }

  public IncomingRequestModelBuilder<T> digest(byte[] digest) {
    this.digest = digest;
    return this;
  }

  public IncomingRequestModel<T, ?, ?> build() {
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

    return new IncomingRequestModel<>(
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
