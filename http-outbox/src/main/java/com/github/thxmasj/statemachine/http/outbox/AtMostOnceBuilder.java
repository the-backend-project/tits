package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ServiceUnavailable;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/// Builder of [AtMostOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the request payload is introduced by [RequestPayloadTypeStep], the type of the response payload by
/// [ResponsePayloadTypeStep], the type of the input by [BatchedStep], the type of the input of the rollback models by
/// [RollbackOnAcceptedModelStep] or [RollbackOnUnknownModelStep], and the type of the data the rollback is dispatched
/// with by [RollbackMessageCreatorStep]. The request messages are not created by the outbox, but given as the input
/// when the request is dispatched. The rollback request messages are created by the outbox, as they depend on the
/// request.
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

    BatchedStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  /// Optional: the request defaults to not being batched, with the request message as its input. A batched request has
  /// a [BatchedRequest] as its input, and references the batch given by its batch id.
  public interface BatchedStep<RQ> extends RollbackOnAcceptedModelStep<TypedHttpRequest<RQ>, RQ> {

    RollbackOnAcceptedModelStep<BatchedRequest<RQ>, RQ> batched();
  }

  /// Optional: the rollback on accepted model defaults to none.
  public interface RollbackOnAcceptedModelStep<I, RQ> extends BuildStep<I, RQ, Void, Void> {

    <RRI> RollbackOnUnknownModelStep<I, RQ, RRI> rollbackOnAcceptedModel(AtLeastOnce<RRI, ?> rollbackOnAcceptedModel);

    <RRI> RollbackMessageCreatorStep<I, RQ, RRI> rollbackOnUnknownModel(AtLeastOnce<RRI, ?> rollbackOnUnknownModel);

  }

  /// Optional: the rollback on unknown model defaults to none.
  public interface RollbackOnUnknownModelStep<I, RQ, RRI> extends RollbackMessageCreatorStep<I, RQ, RRI> {

    RollbackMessageCreatorStep<I, RQ, RRI> rollbackOnUnknownModel(AtLeastOnce<RRI, ?> rollbackOnUnknownModel);

  }

  /// The creator of the input of the rollback models (of type `RRI`), i.e. of the rollback request message, from the
  /// data the rollback is dispatched with (of type `RI`) and the request being rolled back. It is created in the
  /// context of the transition of this outbox request which dispatches the rollback.
  public interface RollbackMessageCreatorStep<I, RQ, RRI> {

    <RI> BuildStep<I, RQ, RI, RRI> rollbackMessageCreator(
        BiFunction<TransitionContext<?>, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
    );

  }

  public interface BuildStep<I, RQ, RI, RRI> {

    AtMostOnce<I, RQ, RI, RRI> build(String name, UUID id);
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
      IsRejectedStep<RQ, RS>, IsRejectedByInvalidResponseStep<RQ, RS>, BatchedStep<RQ> {

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
    public BatchedStep<RQ> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public RollbackOnAcceptedModelStep<BatchedRequest<RQ>, RQ> batched() {
      return new WithInputType<>(InputType.batched(requestPayloadType));
    }

    private WithInputType<TypedHttpRequest<RQ>> unbatched() {
      return new WithInputType<>(InputType.unbatched(requestPayloadType));
    }

    @Override
    public <RRI> RollbackOnUnknownModelStep<TypedHttpRequest<RQ>, RQ, RRI> rollbackOnAcceptedModel(
        AtLeastOnce<RRI, ?> rollbackOnAcceptedModel
    ) {
      return unbatched().rollbackOnAcceptedModel(rollbackOnAcceptedModel);
    }

    @Override
    public <RRI> RollbackMessageCreatorStep<TypedHttpRequest<RQ>, RQ, RRI> rollbackOnUnknownModel(
        AtLeastOnce<RRI, ?> rollbackOnUnknownModel
    ) {
      return unbatched().rollbackOnUnknownModel(rollbackOnUnknownModel);
    }

    @Override
    public AtMostOnce<TypedHttpRequest<RQ>, RQ, Void, Void> build(String name, UUID id) {
      return unbatched().build(name, id);
    }

    private final class WithInputType<I> implements RollbackOnAcceptedModelStep<I, RQ> {

      private final InputType<I, RQ> inputType;

      private WithInputType(InputType<I, RQ> inputType) {
        this.inputType = inputType;
      }

      private <RI, RRI> BuildStep<I, RQ, RI, RRI> buildStep(
          AtLeastOnce<RRI, ?> rollbackOnAcceptedModel,
          AtLeastOnce<RRI, ?> rollbackOnUnknownModel,
          BiFunction<TransitionContext<?>, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
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
            inputType,
            rollbackOnAcceptedModel,
            rollbackOnUnknownModel,
            rollbackMessageCreator
        );
      }

      private <RRI> RollbackMessageCreatorStep<I, RQ, RRI> rollbackMessageCreatorStep(
          AtLeastOnce<RRI, ?> rollbackOnAcceptedModel,
          AtLeastOnce<RRI, ?> rollbackOnUnknownModel
      ) {
        return new RollbackMessageCreatorStep<>() {
          @Override
          public <RI> BuildStep<I, RQ, RI, RRI> rollbackMessageCreator(
              BiFunction<TransitionContext<?>, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
          ) {
            return buildStep(rollbackOnAcceptedModel, rollbackOnUnknownModel, rollbackMessageCreator);
          }
        };
      }

      @Override
      public <RRI> RollbackOnUnknownModelStep<I, RQ, RRI> rollbackOnAcceptedModel(
          AtLeastOnce<RRI, ?> rollbackOnAcceptedModel
      ) {
        return new RollbackOnUnknownModelStep<>() {
          @Override
          public RollbackMessageCreatorStep<I, RQ, RRI> rollbackOnUnknownModel(AtLeastOnce<RRI, ?> rollbackOnUnknownModel) {
            return rollbackMessageCreatorStep(rollbackOnAcceptedModel, rollbackOnUnknownModel);
          }

          @Override
          public <RI> BuildStep<I, RQ, RI, RRI> rollbackMessageCreator(
              BiFunction<TransitionContext<?>, Tuple2<RI, TypedHttpRequest<RQ>>, Mono<RRI>> rollbackMessageCreator
          ) {
            return buildStep(rollbackOnAcceptedModel, null, rollbackMessageCreator);
          }
        };
      }

      @Override
      public <RRI> RollbackMessageCreatorStep<I, RQ, RRI> rollbackOnUnknownModel(AtLeastOnce<RRI, ?> rollbackOnUnknownModel) {
        return rollbackMessageCreatorStep(null, rollbackOnUnknownModel);
      }

      @Override
      public AtMostOnce<I, RQ, Void, Void> build(String name, UUID id) {
        return this.<Void, Void>buildStep(null, null, null).build(name, id);
      }
    }
  }

}
