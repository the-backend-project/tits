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
/// The type of the request payload is introduced by [RequestPayloadTypeStep], the type of the response payload by
/// [ResponsePayloadTypeStep], and the type of the data which the request message is created from by [MessageCreatorStep].
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

    <RQ> ResponsePayloadTypeStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType);
  }

  public interface ResponsePayloadTypeStep<RQ> {

    <RS> MessageCreatorStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType);
  }

  public interface MessageCreatorStep<RQ, RS> {

    default <I> RepeatMessageCreatorStep<I, RQ, RS> messageCreator(Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator) {
      return messageCreatorReactive(messageCreator.andThen(Mono::just));
    }

    <I> RepeatMessageCreatorStep<I, RQ, RS> messageCreatorReactive(Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator);
  }

  /// Optional: the repeated message defaults to the message of the original attempt.
  public interface RepeatMessageCreatorStep<I, RQ, RS> extends ForwarderStep<I, RQ, RS> {

    ForwarderStep<I, RQ, RS> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    );
  }

  public interface ForwarderStep<I, RQ, RS> {

    InflightTimeoutStep<I, RQ, RS> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<I, RQ, RS> extends ProcessModelStep<I, RQ, RS> {

    ProcessModelStep<I, RQ, RS> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<I, RQ, RS> extends ContentParserStep<I, RQ, RS> {

    ContentParserStep<I, RQ, RS> processModel(EntityModel processModel);
  }

  public interface ContentParserStep<I, RQ, RS> {

    OnSuccessStep<I, RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser);

    OnSuccessStep<I, RQ, RS> contentParser(
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
    );
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<I, RQ, RS> extends IsAcceptedStep<I, RQ, RS> {

    <S> IsAcceptedStep<I, RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    IsAcceptedStep<I, RQ, RS> onSuccess(EventType<Void, ?> eventType);
  }

  public interface IsAcceptedStep<I, RQ, RS> {

    IsFailureTransientStep<I, RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted);
  }

  public interface IsFailureTransientStep<I, RQ, RS> {

    IsRejectedByInvalidResponseStep<I, RQ, RS> isFailureTransient(Predicate<TypedHttpResponse<RS>> isFailureTransient);
  }

  public interface IsRejectedByInvalidResponseStep<I, RQ, RS> {

    IsFailureByInvalidResponseTransientStep<I, RQ, RS> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  public interface IsFailureByInvalidResponseTransientStep<I, RQ, RS> {

    IsAttemptAvailableStep<I, RQ, RS> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    );
  }

  public interface IsAttemptAvailableStep<I, RQ, RS> {

    BackoffAlgorithmStep<I, RQ, RS> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable);
  }

  public interface BackoffAlgorithmStep<I, RQ, RS> {

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
    public <RQ> ResponsePayloadTypeStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType) {
      return new WithRequestPayloadType<>(name, id, requestPayloadType);
    }
  }

  private static final class WithRequestPayloadType<RQ> implements ResponsePayloadTypeStep<RQ> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;

    private WithRequestPayloadType(String name, UUID id, DataType<RQ> requestPayloadType) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
    }

    @Override
    public <RS> MessageCreatorStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType) {
      return new WithResponsePayloadType<>(name, id, requestPayloadType, responsePayloadType);
    }
  }

  private static final class WithResponsePayloadType<RQ, RS> implements MessageCreatorStep<RQ, RS> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;

    private WithResponsePayloadType(
        String name,
        UUID id,
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
    }

    @Override
    public <I> RepeatMessageCreatorStep<I, RQ, RS> messageCreatorReactive(
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator
    ) {
      return new WithMessageCreator<>(name, id, requestPayloadType, responsePayloadType, messageCreator);
    }
  }

  private static final class WithMessageCreator<I, RQ, RS> implements RepeatMessageCreatorStep<I, RQ, RS>,
      InflightTimeoutStep<I, RQ, RS> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
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
        DataType<RS> responsePayloadType,
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
      this.messageCreator = messageCreator;
    }

    @Override
    public ForwarderStep<I, RQ, RS> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    ) {
      this.repeatMessageCreator = repeatMessageCreator;
      return this;
    }

    @Override
    public InflightTimeoutStep<I, RQ, RS> forwarder(HttpClient forwarder) {
      this.forwarder = forwarder;
      return this;
    }

    @Override
    public ProcessModelStep<I, RQ, RS> inflightTimeout(Duration inflightTimeout) {
      this.inflightTimeout = inflightTimeout;
      return this;
    }

    @Override
    public ContentParserStep<I, RQ, RS> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public OnSuccessStep<I, RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser) {
      return new WithContentParser<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          contentParser,
          null
      );
    }

    @Override
    public OnSuccessStep<I, RQ, RS> contentParser(
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
    ) {
      return new WithContentParser<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          null,
          contentParser
      );
    }
  }

  private static final class WithContentParser<I, RQ, RS> implements OnSuccessStep<I, RQ, RS>, IsFailureTransientStep<I, RQ, RS>,
      IsRejectedByInvalidResponseStep<I, RQ, RS>, IsFailureByInvalidResponseTransientStep<I, RQ, RS>, IsAttemptAvailableStep<I, RQ, RS>,
      BackoffAlgorithmStep<I, RQ, RS>, BuildStep<I> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private final Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private final BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Function<HttpResponseMessage, Validated<RS>> contentParser;
    private final BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser;
    private Callback<TypedHttpResponse<RS>, ?> onSuccess;
    private Predicate<TypedHttpResponse<RS>> isAccepted;
    private Predicate<TypedHttpResponse<RS>> isFailureTransient;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;
    private Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient;
    private Predicate<RetryContext> isAttemptAvailable;
    private Function<RetryContext, Duration> backoffAlgorithm;

    private WithContentParser(
        String name,
        UUID id,
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType,
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Function<HttpResponseMessage, Validated<RS>> contentParser,
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
      this.messageCreator = messageCreator;
      this.repeatMessageCreator = repeatMessageCreator;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.contentParser = contentParser;
      this.requestAwareContentParser = requestAwareContentParser;
    }

    @Override
    public <S> IsAcceptedStep<I, RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsAcceptedStep<I, RQ, RS> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public IsFailureTransientStep<I, RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted) {
      this.isAccepted = isAccepted;
      return this;
    }

    @Override
    public IsRejectedByInvalidResponseStep<I, RQ, RS> isFailureTransient(
        Predicate<TypedHttpResponse<RS>> isFailureTransient
    ) {
      this.isFailureTransient = isFailureTransient;
      return this;
    }

    @Override
    public IsFailureByInvalidResponseTransientStep<I, RQ, RS> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public IsAttemptAvailableStep<I, RQ, RS> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    ) {
      this.isFailureByInvalidResponseTransient = isFailureByInvalidResponseTransient;
      return this;
    }

    @Override
    public BackoffAlgorithmStep<I, RQ, RS> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable) {
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
          responsePayloadType,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          onSuccess,
          contentParser,
          requestAwareContentParser,
          isAccepted,
          isFailureTransient,
          isRejectedByInvalidResponse,
          isFailureByInvalidResponseTransient,
          isAttemptAvailable,
          backoffAlgorithm
      );
    }
  }
}
