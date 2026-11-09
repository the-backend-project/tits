package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ServiceUnavailable;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/// Builder of [AtMostOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the request payload is introduced by [RequestPayloadTypeStep], the type of the response payload by
/// [ResponsePayloadTypeStep], and the type of the request payload of the rollback models
/// by [RollbackOnAcceptedModelStep] or [RollbackOnUnknownModelStep]. Neither the input nor the rollback input is
/// created by the outbox request; see [Dispatcher].
public final class AtMostOnceBuilder {

  private AtMostOnceBuilder() {}

  static RequestPayloadTypeStep create() {
    return new WithNothing();
  }

  public interface RequestPayloadTypeStep {

    <RQ> ResponsePayloadTypeStep<RQ> requestPayloadType(DataType<RQ> requestPayloadType);
  }

  public interface ResponsePayloadTypeStep<RQ> {

    <RS> ForwarderStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType);
  }

  public interface ForwarderStep<RQ, RS> {

    InflightTimeoutStep<RQ, RS> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<RQ, RS> extends ProcessModelStep<RQ, RS> {

    ProcessModelStep<RQ, RS> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<RQ, RS> extends OnPeerUnavailableStep<RQ, RS> {

    OnPeerUnavailableStep<RQ, RS> processModel(EntityModel processModel);
  }

  /// Optional: the peer unavailable callback defaults to ServiceUnavailable.
  public interface OnPeerUnavailableStep<RQ, RS> extends OnMissingResponseStep<RQ, RS> {

    <S> OnMissingResponseStep<RQ, RS> onPeerUnavailable(EventType<S, ?> eventType, Function<EntityModel, S> dataAdapter);

    OnMissingResponseStep<RQ, RS> onPeerUnavailable(EventType<Void, ?> eventType);

  }

  /// Optional: the missing response callback defaults to TimeoutExpired.
  public interface OnMissingResponseStep<RQ, RS> extends OnInvalidResponseRejectionStep<RQ, RS> {

    <S> OnInvalidResponseRejectionStep<RQ, RS> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    );

    OnInvalidResponseRejectionStep<RQ, RS> onMissingResponse(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response rejection callback defaults to InvalidResponse.
  public interface OnInvalidResponseRejectionStep<RQ, RS> extends OnInvalidResponseUnknownStep<RQ, RS> {

    <S> OnInvalidResponseUnknownStep<RQ, RS> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    OnInvalidResponseUnknownStep<RQ, RS> onInvalidResponseRejection(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response unknown callback defaults to InvalidResponse.
  public interface OnInvalidResponseUnknownStep<RQ, RS> extends ContentParserStep<RQ, RS> {

    <S> ContentParserStep<RQ, RS> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    ContentParserStep<RQ, RS> onInvalidResponseUnknown(EventType<Void, ?> eventType);
  }

  public interface ContentParserStep<RQ, RS> {

    OnSuccessStep<RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser);

    OnSuccessStep<RQ, RS> contentParser(
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> contentParser
    );
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<RQ, RS> extends OnFailureStep<RQ, RS> {

    <S> OnFailureStep<RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    OnFailureStep<RQ, RS> onSuccess(EventType<Void, ?> eventType);
  }

  /// Optional: the failure callback defaults to none.
  public interface OnFailureStep<RQ, RS> extends OnValidResponseUnknownStep<RQ, RS> {

    <S> OnValidResponseUnknownStep<RQ, RS> onFailure(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    OnValidResponseUnknownStep<RQ, RS> onFailure(EventType<Void, ?> eventType);
  }

  /// Optional: the valid response unknown callback defaults to none.
  public interface OnValidResponseUnknownStep<RQ, RS> extends IsAcceptedStep<RQ, RS> {

    <S> IsAcceptedStep<RQ, RS> onValidResponseUnknown(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    IsAcceptedStep<RQ, RS> onValidResponseUnknown(EventType<Void, ?> eventType);
  }

  public interface IsAcceptedStep<RQ, RS> {

    IsRejectedStep<RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted);
  }

  public interface IsRejectedStep<RQ, RS> {

    IsRejectedByInvalidResponseStep<RQ, RS> isRejected(Predicate<TypedHttpResponse<RS>> isRejected);
  }

  public interface IsRejectedByInvalidResponseStep<RQ, RS> {

    RollbackOnAcceptedModelStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  /// Optional: the rollback on accepted model defaults to none.
  public interface RollbackOnAcceptedModelStep<RQ> extends BuildStep<RQ, Void> {

    <RRQ> RollbackOnUnknownModelStep<RQ, RRQ> rollbackOnAcceptedModel(AtLeastOnce<RRQ> rollbackOnAcceptedModel);

    <RRQ> BuildStep<RQ, RRQ> rollbackOnUnknownModel(AtLeastOnce<RRQ> rollbackOnUnknownModel);

  }

  /// Optional: the rollback on unknown model defaults to none.
  public interface RollbackOnUnknownModelStep<RQ, RRQ> extends BuildStep<RQ, RRQ> {

    BuildStep<RQ, RRQ> rollbackOnUnknownModel(AtLeastOnce<RRQ> rollbackOnUnknownModel);

  }

  public interface BuildStep<RQ, RRQ> {

    AtMostOnce<RQ, RRQ> build(String name, UUID id);
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
    public <RS> ForwarderStep<RQ, RS> responsePayloadType(DataType<RS> responsePayloadType) {
      return new WithPayloadTypes<>(requestPayloadType, responsePayloadType);
    }
  }

  private static final class WithPayloadTypes<RQ, RS> implements ForwarderStep<RQ, RS>,
      InflightTimeoutStep<RQ, RS> {

    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;
    private Callback<EntityModel, ?> onPeerUnavailable = new Callback<>(ServiceUnavailable, _ -> null);
    private Callback<EntityModel, ?> onMissingResponse = new Callback<>(TimeoutExpired, _ -> null);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection = new Callback<>(InvalidResponse, Tuple2::t2);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown = new Callback<>(InvalidResponse, Tuple2::t2);

    private WithPayloadTypes(
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType
    ) {
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
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
    public OnPeerUnavailableStep<RQ, RS> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public <S> OnMissingResponseStep<RQ, RS> onPeerUnavailable(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onPeerUnavailable = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnMissingResponseStep<RQ, RS> onPeerUnavailable(EventType<Void, ?> eventType) {
      this.onPeerUnavailable = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseRejectionStep<RQ, RS> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onMissingResponse = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseRejectionStep<RQ, RS> onMissingResponse(EventType<Void, ?> eventType) {
      this.onMissingResponse = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseUnknownStep<RQ, RS> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseRejection = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseUnknownStep<RQ, RS> onInvalidResponseRejection(EventType<Void, ?> eventType) {
      return onInvalidResponseRejection(eventType, _ -> null);
    }

    @Override
    public <S> ContentParserStep<RQ, RS> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseUnknown = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public ContentParserStep<RQ, RS> onInvalidResponseUnknown(EventType<Void, ?> eventType) {
      return onInvalidResponseUnknown(eventType, _ -> null);
    }

    @Override
    public OnSuccessStep<RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser) {
      return new WithContentParser<>(
          requestPayloadType,
          responsePayloadType,
          forwarder,
          inflightTimeout,
          processModel,
          onPeerUnavailable,
          onMissingResponse,
          onInvalidResponseRejection,
          onInvalidResponseUnknown,
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
          forwarder,
          inflightTimeout,
          processModel,
          onPeerUnavailable,
          onMissingResponse,
          onInvalidResponseRejection,
          onInvalidResponseUnknown,
          null,
          contentParser
      );
    }
  }

  private static final class WithContentParser<RQ, RS> implements OnSuccessStep<RQ, RS>,
      OnFailureStep<RQ, RS>, OnValidResponseUnknownStep<RQ, RS>, IsAcceptedStep<RQ, RS>,
      IsRejectedStep<RQ, RS>, IsRejectedByInvalidResponseStep<RQ, RS>, RollbackOnAcceptedModelStep<RQ> {

    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Callback<EntityModel, ?> onPeerUnavailable;
    private final Callback<EntityModel, ?> onMissingResponse;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown;
    private final Function<HttpResponseMessage, Validated<RS>> contentParser;
    private final BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser;
    private Callback<TypedHttpResponse<RS>, ?> onSuccess;
    private Callback<TypedHttpResponse<RS>, ?> onFailure;
    private Callback<TypedHttpResponse<RS>, ?> onValidResponseUnknown;
    private Predicate<TypedHttpResponse<RS>> isAccepted;
    private Predicate<TypedHttpResponse<RS>> isRejected;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;

    private WithContentParser(
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Callback<EntityModel, ?> onPeerUnavailable,
        Callback<EntityModel, ?> onMissingResponse,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown,
        Function<HttpResponseMessage, Validated<RS>> contentParser,
        BiFunction<TypedHttpRequest<RQ>, HttpResponseMessage, Validated<RS>> requestAwareContentParser
    ) {
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.onPeerUnavailable = onPeerUnavailable;
      this.onMissingResponse = onMissingResponse;
      this.onInvalidResponseRejection = onInvalidResponseRejection;
      this.onInvalidResponseUnknown = onInvalidResponseUnknown;
      this.contentParser = contentParser;
      this.requestAwareContentParser = requestAwareContentParser;
    }

    @Override
    public <S> OnFailureStep<RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnFailureStep<RQ, RS> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public <S> OnValidResponseUnknownStep<RQ, RS> onFailure(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onFailure = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnValidResponseUnknownStep<RQ, RS> onFailure(EventType<Void, ?> eventType) {
      return onFailure(eventType, _ -> null);
    }

    @Override
    public <S> IsAcceptedStep<RQ, RS> onValidResponseUnknown(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onValidResponseUnknown = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsAcceptedStep<RQ, RS> onValidResponseUnknown(EventType<Void, ?> eventType) {
      return onValidResponseUnknown(eventType, _ -> null);
    }

    @Override
    public IsRejectedStep<RQ, RS> isAccepted(Predicate<TypedHttpResponse<RS>> isAccepted) {
      this.isAccepted = isAccepted;
      return this;
    }

    @Override
    public IsRejectedByInvalidResponseStep<RQ, RS> isRejected(Predicate<TypedHttpResponse<RS>> isRejected) {
      this.isRejected = isRejected;
      return this;
    }

    @Override
    public RollbackOnAcceptedModelStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    private <RRQ> BuildStep<RQ, RRQ> buildStep(
        AtLeastOnce<RRQ> rollbackOnAcceptedModel,
        AtLeastOnce<RRQ> rollbackOnUnknownModel
    ) {
      return (name, id) -> new AtMostOnce<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          forwarder,
          inflightTimeout,
          processModel,
          onPeerUnavailable,
          onMissingResponse,
          onSuccess,
          onFailure,
          onValidResponseUnknown,
          onInvalidResponseRejection,
          onInvalidResponseUnknown,
          contentParser,
          requestAwareContentParser,
          isAccepted,
          isRejected,
          isRejectedByInvalidResponse,
          rollbackOnAcceptedModel,
          rollbackOnUnknownModel
      );
    }

    @Override
    public <RRQ> RollbackOnUnknownModelStep<RQ, RRQ> rollbackOnAcceptedModel(AtLeastOnce<RRQ> rollbackOnAcceptedModel) {
      return new RollbackOnUnknownModelStep<>() {
        @Override
        public BuildStep<RQ, RRQ> rollbackOnUnknownModel(AtLeastOnce<RRQ> rollbackOnUnknownModel) {
          return buildStep(rollbackOnAcceptedModel, rollbackOnUnknownModel);
        }

        @Override
        public AtMostOnce<RQ, RRQ> build(String name, UUID id) {
          return buildStep(rollbackOnAcceptedModel, null).build(name, id);
        }
      };
    }

    @Override
    public <RRQ> BuildStep<RQ, RRQ> rollbackOnUnknownModel(AtLeastOnce<RRQ> rollbackOnUnknownModel) {
      return buildStep(null, rollbackOnUnknownModel);
    }

    @Override
    public AtMostOnce<RQ, Void> build(String name, UUID id) {
      return this.<Void>buildStep(null, null).build(name, id);
    }
  }

}
