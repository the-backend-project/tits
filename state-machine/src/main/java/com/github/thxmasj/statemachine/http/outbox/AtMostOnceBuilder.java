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
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/// Builder of [AtMostOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the request payload is introduced by [RequestPayloadTypeStep], the type of the response payload by
/// [ResponsePayloadTypeStep], the type of the data which the request message is created from by [MessageCreatorStep],
/// and the type of the data which the rollback request message is created from by [RollbackModelStep].
public final class AtMostOnceBuilder {

  private AtMostOnceBuilder() {}

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

    default <I> ForwarderStep<I, RQ, RS> messageCreator(Function<TransitionContext<I>, TypedHttpRequest<RQ>> messageCreator) {
      return messageCreatorReactive(messageCreator.andThen(Mono::just));
    }

    <I> ForwarderStep<I, RQ, RS> messageCreatorReactive(Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator);
  }

  public interface ForwarderStep<I, RQ, RS> {

    InflightTimeoutStep<I, RQ, RS> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<I, RQ, RS> extends ProcessModelStep<I, RQ, RS> {

    ProcessModelStep<I, RQ, RS> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<I, RQ, RS> extends OnPeerUnavailableStep<I, RQ, RS> {

    OnPeerUnavailableStep<I, RQ, RS> processModel(EntityModel processModel);
  }

  /// Optional: the peer unavailable callback defaults to ServiceUnavailable.
  public interface OnPeerUnavailableStep<I, RQ, RS> extends OnMissingResponseStep<I, RQ, RS> {

    <S> OnMissingResponseStep<I, RQ, RS> onPeerUnavailable(EventType<S, ?> eventType, Function<EntityModel, S> dataAdapter);

    OnMissingResponseStep<I, RQ, RS> onPeerUnavailable(EventType<Void, ?> eventType);

  }

  /// Optional: the missing response callback defaults to TimeoutExpired.
  public interface OnMissingResponseStep<I, RQ, RS> extends OnInvalidResponseRejectionStep<I, RQ, RS> {

    <S> OnInvalidResponseRejectionStep<I, RQ, RS> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    );

    OnInvalidResponseRejectionStep<I, RQ, RS> onMissingResponse(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response rejection callback defaults to InvalidResponse.
  public interface OnInvalidResponseRejectionStep<I, RQ, RS> extends OnInvalidResponseUnknownStep<I, RQ, RS> {

    <S> OnInvalidResponseUnknownStep<I, RQ, RS> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    OnInvalidResponseUnknownStep<I, RQ, RS> onInvalidResponseRejection(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response unknown callback defaults to InvalidResponse.
  public interface OnInvalidResponseUnknownStep<I, RQ, RS> extends ContentParserStep<I, RQ, RS> {

    <S> ContentParserStep<I, RQ, RS> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    ContentParserStep<I, RQ, RS> onInvalidResponseUnknown(EventType<Void, ?> eventType);
  }

  public interface ContentParserStep<I, RQ, RS> {

    OnSuccessStep<I, RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser);
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<I, RQ, RS> extends OnFailureStep<I, RQ, RS> {

    <S> OnFailureStep<I, RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    OnFailureStep<I, RQ, RS> onSuccess(EventType<Void, ?> eventType);
  }

  /// Optional: the failure callback defaults to none.
  public interface OnFailureStep<I, RQ, RS> extends IsDeliveredStep<I, RQ, RS> {

    <S> IsDeliveredStep<I, RQ, RS> onFailure(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    );

    IsDeliveredStep<I, RQ, RS> onFailure(EventType<Void, ?> eventType);
  }

  public interface IsDeliveredStep<I, RQ, RS> {

    IsRejectedByInvalidResponseStep<I, RQ, RS> isDelivered(Predicate<TypedHttpResponse<RS>> isDelivered);
  }

  public interface IsRejectedByInvalidResponseStep<I, RQ, RS> {

    RollbackModelStep<I, RQ, RS> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  /// Optional: the rollback model defaults to none.
  public interface RollbackModelStep<I, RQ, RS> extends BuildStep<I, Void> {

    <RI> BuildStep<I, RI> rollbackModel(AtLeastOnce<RI> rollbackModel);

    <RI> BuildStep<I, RI> rollbackModel(AtLeastOnceBuilder.BuildStep<RI> rollbackModelBuilder);
  }

  public interface BuildStep<I, RI> {

    AtMostOnce<I, RI> build();
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
    public <I> ForwarderStep<I, RQ, RS> messageCreatorReactive(Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator) {
      return new WithMessageCreator<>(name, id, requestPayloadType, responsePayloadType, messageCreator);
    }
  }

  private static final class WithMessageCreator<I, RQ, RS> implements ForwarderStep<I, RQ, RS>,
      InflightTimeoutStep<I, RQ, RS> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private final Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;
    private Callback<EntityModel, ?> onPeerUnavailable = new Callback<>(ServiceUnavailable, d -> d);
    private Callback<EntityModel, ?> onMissingResponse = new Callback<>(TimeoutExpired, _ -> null);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection = new Callback<>(InvalidResponse, Tuple2::t2);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown = new Callback<>(InvalidResponse, Tuple2::t2);

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
    public OnPeerUnavailableStep<I, RQ, RS> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public <S> OnMissingResponseStep<I, RQ, RS> onPeerUnavailable(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onPeerUnavailable = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnMissingResponseStep<I, RQ, RS> onPeerUnavailable(EventType<Void, ?> eventType) {
      this.onPeerUnavailable = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseRejectionStep<I, RQ, RS> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onMissingResponse = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseRejectionStep<I, RQ, RS> onMissingResponse(EventType<Void, ?> eventType) {
      this.onMissingResponse = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseUnknownStep<I, RQ, RS> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseRejection = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseUnknownStep<I, RQ, RS> onInvalidResponseRejection(EventType<Void, ?> eventType) {
      return onInvalidResponseRejection(eventType, _ -> null);
    }

    @Override
    public <S> ContentParserStep<I, RQ, RS> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseUnknown = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public ContentParserStep<I, RQ, RS> onInvalidResponseUnknown(EventType<Void, ?> eventType) {
      return onInvalidResponseUnknown(eventType, _ -> null);
    }

    @Override
    public OnSuccessStep<I, RQ, RS> contentParser(Function<HttpResponseMessage, Validated<RS>> contentParser) {
      return new WithContentParser<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          messageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          onPeerUnavailable,
          onMissingResponse,
          onInvalidResponseRejection,
          onInvalidResponseUnknown,
          contentParser
      );
    }
  }

  private static final class WithContentParser<I, RQ, RS> implements OnSuccessStep<I, RQ, RS>,
      IsRejectedByInvalidResponseStep<I, RQ, RS>, RollbackModelStep<I, RQ, RS> {

    private final String name;
    private final UUID id;
    private final DataType<RQ> requestPayloadType;
    private final DataType<RS> responsePayloadType;
    private final Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Callback<EntityModel, ?> onPeerUnavailable;
    private final Callback<EntityModel, ?> onMissingResponse;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown;
    private final Function<HttpResponseMessage, Validated<RS>> contentParser;
    private Callback<TypedHttpResponse<RS>, ?> onSuccess;
    private Callback<TypedHttpResponse<RS>, ?> onFailure;
    private Predicate<TypedHttpResponse<RS>> isDelivered;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;

    private WithContentParser(
        String name,
        UUID id,
        DataType<RQ> requestPayloadType,
        DataType<RS> responsePayloadType,
        Function<TransitionContext<I>, Mono<TypedHttpRequest<RQ>>> messageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Callback<EntityModel, ?> onPeerUnavailable,
        Callback<EntityModel, ?> onMissingResponse,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown,
        Function<HttpResponseMessage, Validated<RS>> contentParser
    ) {
      this.name = name;
      this.id = id;
      this.requestPayloadType = requestPayloadType;
      this.responsePayloadType = responsePayloadType;
      this.messageCreator = messageCreator;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.onPeerUnavailable = onPeerUnavailable;
      this.onMissingResponse = onMissingResponse;
      this.onInvalidResponseRejection = onInvalidResponseRejection;
      this.onInvalidResponseUnknown = onInvalidResponseUnknown;
      this.contentParser = contentParser;
    }

    @Override
    public <S> OnFailureStep<I, RQ, RS> onSuccess(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnFailureStep<I, RQ, RS> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public <S> IsDeliveredStep<I, RQ, RS> onFailure(
        EventType<S, ?> eventType,
        Function<TypedHttpResponse<RS>, S> dataAdapter
    ) {
      this.onFailure = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsDeliveredStep<I, RQ, RS> onFailure(EventType<Void, ?> eventType) {
      return onFailure(eventType, _ -> null);
    }

    @Override
    public IsRejectedByInvalidResponseStep<I, RQ, RS> isDelivered(Predicate<TypedHttpResponse<RS>> isDelivered) {
      this.isDelivered = isDelivered;
      return this;
    }

    @Override
    public RollbackModelStep<I, RQ, RS> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public <RI> BuildStep<I, RI> rollbackModel(AtLeastOnce<RI> rollbackModel) {
      return () -> new AtMostOnce<>(
          name,
          id,
          requestPayloadType,
          responsePayloadType,
          messageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          onPeerUnavailable,
          onMissingResponse,
          onSuccess,
          onFailure,
          onInvalidResponseRejection,
          onInvalidResponseUnknown,
          contentParser,
          isDelivered,
          isRejectedByInvalidResponse,
          rollbackModel
      );
    }

    @Override
    public <RI> BuildStep<I, RI> rollbackModel(AtLeastOnceBuilder.BuildStep<RI> rollbackModelBuilder) {
      return rollbackModel(rollbackModelBuilder.build());
    }

    @Override
    public AtMostOnce<I, Void> build() {
      return rollbackModel((AtLeastOnce<Void>) null).build();
    }
  }

}
