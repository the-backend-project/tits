package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.RetryContext;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/// Builder of [AtLeastOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the data which the request message is created from is introduced by [MessageCreatorStep] and the type
/// of the parsed response content by [ContentParserStep].
public final class AtLeastOnceBuilder {

  private AtLeastOnceBuilder() {}

  static NameStep create() {
    return new WithNothing();
  }

  public interface NameStep {

    IdStep name(String name);
  }

  public interface IdStep {

    RequestPayloadTypeStep id(UUID id);
  }

  public interface RequestPayloadTypeStep {

    <RQ> MessageCreatorStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType);
  }

  public interface MessageCreatorStep<RQ> {

    default <I> RepeatMessageCreatorStep<I, RQ> messageCreator(Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator) {
      return messageCreatorReactive(messageCreator.andThen(Mono::just));
    }

    <I> RepeatMessageCreatorStep<I, RQ> messageCreatorReactive(Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator);
  }

  /// Optional: the repeated message defaults to the message of the original attempt.
  public interface RepeatMessageCreatorStep<I, RQ> extends ForwarderStep<I, RQ> {

    ForwarderStep<I, RQ> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    );
  }

  public interface ForwarderStep<I, RQ> {

    InflightTimeoutStep<I, RQ> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<I, RQ> extends ProcessModelStep<I, RQ> {

    ProcessModelStep<I, RQ> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<I, RQ> extends ContentParserStep<I, RQ> {

    ContentParserStep<I, RQ> processModel(EntityModel processModel);
  }

  public interface ContentParserStep<I, RQ> {

    <R> OnSuccessStep<I, R, RQ> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser);
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<I, R, RQ> extends IsDeliveredStep<I, R, RQ> {

    <S> IsDeliveredStep<I, R, RQ> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<R>, S> dataAdapter
    );

    IsDeliveredStep<I, R, RQ> onSuccess(EventType<Void, ?> eventType);
  }

  public interface IsDeliveredStep<I, R, RQ> {

    IsFailureTransientStep<I, R, RQ> isDelivered(Predicate<TypedHttpResponse<R>> isDelivered);
  }

  public interface IsFailureTransientStep<I, R, RQ> {

    IsRejectedByInvalidResponseStep<I, RQ> isFailureTransient(Predicate<TypedHttpResponse<R>> isFailureTransient);
  }

  public interface IsRejectedByInvalidResponseStep<I, RQ> {

    IsFailureByInvalidResponseTransientStep<I, RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  public interface IsFailureByInvalidResponseTransientStep<I, RQ> {

    IsAttemptAvailableStep<I, RQ> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    );
  }

  public interface IsAttemptAvailableStep<I, RQ> {

    BackoffAlgorithmStep<I, RQ> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable);
  }

  public interface BackoffAlgorithmStep<I, RQ> {

    BuildStep<I> backoffAlgorithm(Function<RetryContext, Duration> backoffAlgorithm);
  }

  public interface BuildStep<I> {

    AtLeastOnce<I> build();
  }

  private static final class WithNothing implements NameStep, IdStep, RequestPayloadTypeStep {

    private String name;
    private UUID id;

    @Override
    public IdStep name(String name) {
      this.name = name;
      return this;
    }

    @Override
    public RequestPayloadTypeStep id(UUID id) {
      this.id = id;
      return this;
    }

    @Override
    public <RQ> MessageCreatorStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType) {
      return new WithMessageCreatorStep<>(name, id, requestPayloadType);
    }
  }

  private static final class WithMessageCreatorStep<RQ> implements MessageCreatorStep<RQ> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;

    private WithMessageCreatorStep(String name, UUID id, DataType<RQ> requestPayloadType) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
    }

    @Override
    public <I> RepeatMessageCreatorStep<I, RQ> messageCreatorReactive(
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator
    ) {
      return new WithMessageCreator<>(name, id, requestPayloadType, messageCreator);
    }
  }

  private static final class WithMessageCreator<I, RQ> implements RepeatMessageCreatorStep<I, RQ>,
      InflightTimeoutStep<I, RQ> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator =
        (_, message) -> message;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;

    private WithMessageCreator(
        String name,
        UUID id,
        DataType<RQ> requestPayloadType,
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.messageCreator = messageCreator;
    }

    @Override
    public ForwarderStep<I, RQ> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    ) {
      this.repeatMessageCreator = repeatMessageCreator;
      return this;
    }

    @Override
    public InflightTimeoutStep<I, RQ> forwarder(HttpClient forwarder) {
      this.forwarder = forwarder;
      return this;
    }

    @Override
    public ProcessModelStep<I, RQ> inflightTimeout(Duration inflightTimeout) {
      this.inflightTimeout = inflightTimeout;
      return this;
    }

    @Override
    public ContentParserStep<I, RQ> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public <R> OnSuccessStep<I, R, RQ> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser) {
      return new WithContentParser<>(
          name,
          id,
          requestPayloadType,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          contentParser
      );
    }
  }

  private static final class WithContentParser<I, R, RQ> implements OnSuccessStep<I, R, RQ>, IsFailureTransientStep<I, R, RQ>,
      IsRejectedByInvalidResponseStep<I, RQ>, IsFailureByInvalidResponseTransientStep<I, RQ>, IsAttemptAvailableStep<I, RQ>,
      BackoffAlgorithmStep<I, RQ>, BuildStep<I> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private final BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Function<HttpResponseMessage, Validated<R>> contentParser;
    private Callback<TypedHttpResponse<R>, ?> onSuccess;
    private Predicate<TypedHttpResponse<R>> isDelivered;
    private Predicate<TypedHttpResponse<R>> isFailureTransient;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;
    private Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient;
    private Predicate<RetryContext> isAttemptAvailable;
    private Function<RetryContext, Duration> backoffAlgorithm;

    private WithContentParser(
        String name,
        UUID id,
        DataType<RQ> requestPayloadType,
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Function<HttpResponseMessage, Validated<R>> contentParser
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.messageCreator = messageCreator;
      this.repeatMessageCreator = repeatMessageCreator;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.contentParser = contentParser;
    }

    @Override
    public <S> IsDeliveredStep<I, R, RQ> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<R>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsDeliveredStep<I, R, RQ> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public IsFailureTransientStep<I, R, RQ> isDelivered(Predicate<TypedHttpResponse<R>> isDelivered) {
      this.isDelivered = isDelivered;
      return this;
    }

    @Override
    public IsRejectedByInvalidResponseStep<I, RQ> isFailureTransient(
        Predicate<TypedHttpResponse<R>> isFailureTransient
    ) {
      this.isFailureTransient = isFailureTransient;
      return this;
    }

    @Override
    public IsFailureByInvalidResponseTransientStep<I, RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public IsAttemptAvailableStep<I, RQ> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    ) {
      this.isFailureByInvalidResponseTransient = isFailureByInvalidResponseTransient;
      return this;
    }

    @Override
    public BackoffAlgorithmStep<I, RQ> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable) {
      this.isAttemptAvailable = isAttemptAvailable;
      return this;
    }

    @Override
    public BuildStep<I> backoffAlgorithm(Function<RetryContext, Duration> backoffAlgorithm) {
      this.backoffAlgorithm = backoffAlgorithm;
      return this;
    }

    @Override
    public AtLeastOnce<I> build() {
      return new AtLeastOnce<>(
          name,
          id,
          requestPayloadType,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          onSuccess,
          contentParser,
          isDelivered,
          isFailureTransient,
          isRejectedByInvalidResponse,
          isFailureByInvalidResponseTransient,
          isAttemptAvailable,
          backoffAlgorithm
      );
    }
  }
}
