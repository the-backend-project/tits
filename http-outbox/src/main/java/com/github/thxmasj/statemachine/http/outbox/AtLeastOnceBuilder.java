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

/// Builder of [AtLeastOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the request payload is introduced by [RequestPayloadTypeStep] and the type of the response payload by
/// [ResponsePayloadTypeStep]. The request messages are not created by the outbox, but given as the input when the
/// request is dispatched.
public final class AtLeastOnceBuilder {

  private AtLeastOnceBuilder() {}

  static RequestPayloadTypeStep create() {
    return new WithNothing();
  }

  public interface RequestPayloadTypeStep {

    <RQ> ResponsePayloadTypeStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType);
  }

  public interface ResponsePayloadTypeStep<RQ> {

    <RS> RepeatMessageCreatorStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType);
  }

  /// Optional: the repeated message defaults to the message of the original attempt.
  public interface RepeatMessageCreatorStep<RQ, RS> extends ForwarderStep<RQ, RS> {

    ForwarderStep<RQ, RS> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    );
  }

  public interface ForwarderStep<RQ, RS> {

    InflightTimeoutStep<RQ, RS> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<RQ, RS> extends ProcessModelStep<RQ, RS> {

    ProcessModelStep<RQ, RS> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<RQ, RS> extends ContentParserStep<RQ, RS> {

    ContentParserStep<RQ, RS> processModel(EntityModel processModel);
  }

  public interface ContentParserStep<RQ, RS> {

    OnSuccessStep<RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser);

    OnSuccessStep<RQ, RS> contentParser(
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
    );
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<RQ, RS> extends IsAcceptedStep<RQ, RS> {

    <S> IsAcceptedStep<RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    IsAcceptedStep<RQ, RS> onSuccess(EventType<Void, ?> eventType);
  }

  public interface IsAcceptedStep<RQ, RS> {

    IsFailureTransientStep<RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted);

    //IsFailureTransientStep<RQ, RS> isRollbackAccepted(Predicate<Tuple2<TypedHttpResponse<RS>, Boolean>> isRollbackAccepted);
  }

  public interface IsFailureTransientStep<RQ, RS> {

    IsRejectedByInvalidResponseStep<RQ> isFailureTransient(Predicate<TypedHttpResponse<RS>> isFailureTransient);
  }

  public interface IsRejectedByInvalidResponseStep<RQ> {

    IsFailureByInvalidResponseTransientStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  public interface IsFailureByInvalidResponseTransientStep<RQ> {

    IsAttemptAvailableStep<RQ> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    );
  }

  public interface IsAttemptAvailableStep<RQ> {

    BackoffAlgorithmStep<RQ> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable);
  }

  public interface BackoffAlgorithmStep<RQ> {

    BatchedStep<RQ> backoffAlgorithm(Function<RetryContext, Duration> backoffAlgorithm);
  }

  /// Optional: the request defaults to not being batched. A batched request references the batch given by the batch id
  /// of its input, which then must be given.
  public interface BatchedStep<RQ> extends BuildStep<RQ> {

    BuildStep<RQ> batched();
  }

  /// Optional: the super model defaults to none.
//  public interface SuperModelStep<RQ> extends BuildStep<RQ> {
//
//    BuildStep<RQ> superModel(AtMostOnce<?, ?> superModel);
//  }

  public interface BuildStep<RQ> {

    AtLeastOnce<RQ> build(String name, UUID id);
  }

  private static final class WithNothing implements RequestPayloadTypeStep {

    @Override
    public <RQ> ResponsePayloadTypeStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType) {
      return new WithRequestPayloadType<>(requestPayloadType);
    }
  }

  private static final class WithRequestPayloadType<RQ> implements ResponsePayloadTypeStep<RQ> {

    private final DataType<RQ> requestPayloadType;

    private WithRequestPayloadType(DataType<RQ> requestPayloadType) {
      this.requestPayloadType = requestPayloadType;
    }

    @Override
    public <RS> RepeatMessageCreatorStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType) {
      return new WithPayloadTypes<>(requestPayloadType, responsePayloadType);
    }
  }

  private static final class WithPayloadTypes<RQ, RS> implements RepeatMessageCreatorStep<RQ, RS>,
      InflightTimeoutStep<RQ, RS> {

    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator =
        (_, message) -> message;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;

    private WithPayloadTypes(
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType
    ) {
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
    }

    @Override
    public ForwarderStep<RQ, RS> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator
    ) {
      this.repeatMessageCreator = repeatMessageCreator;
      return this;
    }

    @Override
    public InflightTimeoutStep<RQ, RS> forwarder(HttpClient forwarder) {
      this.forwarder = forwarder;
      return this;
    }

    @Override
    public ProcessModelStep<RQ, RS> inflightTimeout(Duration inflightTimeout) {
      this.inflightTimeout = inflightTimeout;
      return this;
    }

    @Override
    public ContentParserStep<RQ, RS> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public OnSuccessStep<RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser) {
      return new WithContentParser<>(
          requestPayloadType,
          responsePayloadType,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          contentParser,
          null
      );
    }

    @Override
    public OnSuccessStep<RQ, RS> contentParser(
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
    ) {
      return new WithContentParser<>(
          requestPayloadType,
          responsePayloadType,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          null,
          contentParser
      );
    }
  }

  private static final class WithContentParser<RQ, RS> implements OnSuccessStep<RQ, RS>, IsFailureTransientStep<RQ, RS>,
      IsRejectedByInvalidResponseStep<RQ>, IsFailureByInvalidResponseTransientStep<RQ>, IsAttemptAvailableStep<RQ>,
      BackoffAlgorithmStep<RQ>, BatchedStep<RQ>, BuildStep<RQ> {

    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private final BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Function<HttpResponseMessage, Validated<RS>> contentParser;
    private final BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser;
    private Callback<TypedHttpResponse<RS>, ?> onSuccess;
    private Predicate<TypedHttpResponse<RS>> isAccepted;
    //private Predicate<Tuple2<TypedHttpResponse<RS>, Boolean>> isRollbackAccepted;
    private Predicate<TypedHttpResponse<RS>> isFailureTransient;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;
    private Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient;
    private Predicate<RetryContext> isAttemptAvailable;
    private Function<RetryContext, Duration> backoffAlgorithm;
    private boolean batched;
    //private AtMostOnce<?, ?> superModel;

    private WithContentParser(
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType,
        BiFunction<TransitionContext<Void>, TypedHttpRequest<RQ>, TypedHttpRequest<RQ>> repeatMessageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Function<HttpResponseMessage, Validated<RS>> contentParser,
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser
    ) {
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
      this.repeatMessageCreator = repeatMessageCreator;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.contentParser = contentParser;
      this.requestAwareContentParser = requestAwareContentParser;
    }

    @Override
    public <S> IsAcceptedStep<RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsAcceptedStep<RQ, RS> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public IsFailureTransientStep<RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted) {
      this.isAccepted = isAccepted;
      return this;
    }

//    @Override
//    public IsFailureTransientStep<RQ, RS> isRollbackAccepted(Predicate<Tuple2<TypedHttpResponse<RS>, Boolean>> isRollbackAccepted) {
//      this.isRollbackAccepted = isRollbackAccepted;
//      return this;
//    }

    @Override
    public IsRejectedByInvalidResponseStep<RQ> isFailureTransient(
        Predicate<TypedHttpResponse<RS>> isFailureTransient
    ) {
      this.isFailureTransient = isFailureTransient;
      return this;
    }

    @Override
    public IsFailureByInvalidResponseTransientStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public IsAttemptAvailableStep<RQ> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    ) {
      this.isFailureByInvalidResponseTransient = isFailureByInvalidResponseTransient;
      return this;
    }

    @Override
    public BackoffAlgorithmStep<RQ> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable) {
      this.isAttemptAvailable = isAttemptAvailable;
      return this;
    }

    @Override
    public BatchedStep<RQ> backoffAlgorithm(Function<RetryContext, Duration> backoffAlgorithm) {
      this.backoffAlgorithm = backoffAlgorithm;
      return this;
    }

    @Override
    public BuildStep<RQ> batched() {
      this.batched = true;
      return this;
    }

//    @Override
//    public BuildStep<RQ> superModel(AtMostOnce<?, ?> superModel) {
//      this.superModel = superModel;
//      return this;
//    }

    @Override
    public AtLeastOnce<RQ> build(String name, UUID id) {
      return new AtLeastOnce<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          onSuccess,
          contentParser,
          requestAwareContentParser,
          isAccepted,
          //isRollbackAccepted,
          isFailureTransient,
          isRejectedByInvalidResponse,
          isFailureByInvalidResponseTransient,
          isAttemptAvailable,
          backoffAlgorithm,
          batched
          //,superModel
      );
    }
  }
}
