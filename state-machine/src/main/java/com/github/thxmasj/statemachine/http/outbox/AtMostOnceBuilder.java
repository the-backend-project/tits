package com.github.thxmasj.statemachine.http.outbox;

import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ServiceUnavailable;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.TimeoutExpired;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/// Builder of [AtMostOnce] outboxes. The settings are given in the fixed order of the steps below, each step
/// returning the next one, so that the order is enforced by the compiler. A step which has a default is optional and
/// can be skipped, as it also offers the settings of the steps following it.
///
/// The type of the data which the request message is created from is introduced by [MessageCreatorStep], the type of
/// the parsed response content by [ContentParserStep], and the type of the data which the rollback request message is
/// created from by [RollbackModelStep].
public final class AtMostOnceBuilder {

  private AtMostOnceBuilder() {}

  static NameStep create() {
    return new WithNothing();
  }

  public interface NameStep {

    IdStep name(String name);
  }

  public interface IdStep {

    MessageCreatorStep id(UUID id);
  }

  public interface MessageCreatorStep {

    default <I> ForwarderStep<I> messageCreator(Function<TransitionContext<I>, HttpRequestMessage> messageCreator) {
      return messageCreatorReactive(messageCreator.andThen(Mono::just));
    }

    <I> ForwarderStep<I> messageCreatorReactive(Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator);
  }

  public interface ForwarderStep<I> {

    InflightTimeoutStep<I> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<I> extends ProcessModelStep<I> {

    ProcessModelStep<I> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<I> extends OnPeerUnavailableStep<I> {

    OnPeerUnavailableStep<I> processModel(EntityModel processModel);
  }

  /// Optional: the peer unavailable callback defaults to ServiceUnavailable.
  public interface OnPeerUnavailableStep<I> extends OnMissingResponseStep<I> {

    <S> OnMissingResponseStep<I> onPeerUnavailable(EventType<S, ?> eventType, Function<EntityModel, S> dataAdapter);

    OnMissingResponseStep<I> onPeerUnavailable(EventType<Void, ?> eventType);

  }

  /// Optional: the missing response callback defaults to TimeoutExpired.
  public interface OnMissingResponseStep<I> extends OnInvalidResponseRejectionStep<I> {

    <S> OnInvalidResponseRejectionStep<I> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    );

    OnInvalidResponseRejectionStep<I> onMissingResponse(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response rejection callback defaults to InvalidResponse.
  public interface OnInvalidResponseRejectionStep<I> extends OnInvalidResponseUnknownStep<I> {

    <S> OnInvalidResponseUnknownStep<I> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    OnInvalidResponseUnknownStep<I> onInvalidResponseRejection(EventType<Void, ?> eventType);
  }

  /// Optional: the invalid response unknown callback defaults to InvalidResponse.
  public interface OnInvalidResponseUnknownStep<I> extends ContentParserStep<I> {

    <S> ContentParserStep<I> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    );

    ContentParserStep<I> onInvalidResponseUnknown(EventType<Void, ?> eventType);
  }

  public interface ContentParserStep<I> {

    <R> OnSuccessStep<I, R> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser);
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<I, R> extends OnFailureStep<I, R> {

    <S> OnFailureStep<I, R> onSuccess(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    );

    OnFailureStep<I, R> onSuccess(EventType<Void, ?> eventType);
  }

  /// Optional: the failure callback defaults to none.
  public interface OnFailureStep<I, R> extends IsDeliveredStep<I, R> {

    <S> IsDeliveredStep<I, R> onFailure(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    );

    IsDeliveredStep<I, R> onFailure(EventType<Void, ?> eventType);
  }

  public interface IsDeliveredStep<I, R> {

    IsRejectedByInvalidResponseStep<I> isDelivered(Predicate<Tuple2<HttpResponseMessage, R>> isDelivered);
  }

  public interface IsRejectedByInvalidResponseStep<I> {

    RollbackModelStep<I> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  /// Optional: the rollback model defaults to none.
  public interface RollbackModelStep<I> extends BuildStep<I, Void> {

    <RI> BuildStep<I, RI> rollbackModel(AtLeastOnce<RI> rollbackModel);

    <RI> BuildStep<I, RI> rollbackModel(AtLeastOnceBuilder.BuildStep<RI> rollbackModelBuilder);
  }

  public interface BuildStep<I, RI> {

    AtMostOnce<I, RI> build();
  }

  private static final class WithNothing implements NameStep, IdStep, MessageCreatorStep {

    private String name;
    private UUID id;

    @Override
    public IdStep name(String name) {
      this.name = name;
      return this;
    }

    @Override
    public MessageCreatorStep id(UUID id) {
      this.id = id;
      return this;
    }

    @Override
    public <I> ForwarderStep<I> messageCreatorReactive(Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator) {
      return new WithMessageCreator<>(name, id, messageCreator);
    }
  }

  private static final class WithMessageCreator<I> implements ForwarderStep<I>,
      InflightTimeoutStep<I> {

    private final String name;
    private final UUID id;
    private final Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;
    private Callback<EntityModel, ?> onPeerUnavailable = new Callback<>(ServiceUnavailable, d -> d);
    private Callback<EntityModel, ?> onMissingResponse = new Callback<>(TimeoutExpired, _ -> null);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection = new Callback<>(InvalidResponse, Tuple2::t2);
    private Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown = new Callback<>(InvalidResponse, Tuple2::t2);

    private WithMessageCreator(String name, UUID id, Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator) {
      this.name = name;
      this.id = id;
      this.messageCreator = messageCreator;
    }

    @Override
    public InflightTimeoutStep<I> forwarder(HttpClient forwarder) {
      this.forwarder = forwarder;
      return this;
    }

    @Override
    public ProcessModelStep<I> inflightTimeout(Duration inflightTimeout) {
      this.inflightTimeout = inflightTimeout;
      return this;
    }

    @Override
    public OnPeerUnavailableStep<I> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public <S> OnMissingResponseStep<I> onPeerUnavailable(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onPeerUnavailable = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnMissingResponseStep<I> onPeerUnavailable(EventType<Void, ?> eventType) {
      this.onPeerUnavailable = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseRejectionStep<I> onMissingResponse(
        EventType<S, ?> eventType,
        Function<EntityModel, S> dataAdapter
    ) {
      this.onMissingResponse = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseRejectionStep<I> onMissingResponse(EventType<Void, ?> eventType) {
      this.onMissingResponse = new Callback<>(eventType, _ -> null);
      return this;
    }

    @Override
    public <S> OnInvalidResponseUnknownStep<I> onInvalidResponseRejection(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseRejection = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnInvalidResponseUnknownStep<I> onInvalidResponseRejection(EventType<Void, ?> eventType) {
      return onInvalidResponseRejection(eventType, _ -> null);
    }

    @Override
    public <S> ContentParserStep<I> onInvalidResponseUnknown(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, String>, S> dataAdapter
    ) {
      this.onInvalidResponseUnknown = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public ContentParserStep<I> onInvalidResponseUnknown(EventType<Void, ?> eventType) {
      return onInvalidResponseUnknown(eventType, _ -> null);
    }

    @Override
    public <R> OnSuccessStep<I, R> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser) {
      return new WithContentParser<>(
          name,
          id,
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

  private static final class WithContentParser<I, R> implements OnSuccessStep<I, R>,
      IsRejectedByInvalidResponseStep<I>, RollbackModelStep<I> {

    private final String name;
    private final UUID id;
    private final Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Callback<EntityModel, ?> onPeerUnavailable;
    private final Callback<EntityModel, ?> onMissingResponse;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection;
    private final Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown;
    private final Function<HttpResponseMessage, Validated<R>> contentParser;
    private Callback<Tuple2<HttpResponseMessage, R>, ?> onSuccess;
    private Callback<Tuple2<HttpResponseMessage, R>, ?> onFailure;
    private Predicate<Tuple2<HttpResponseMessage, R>> isDelivered;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;

    private WithContentParser(
        String name,
        UUID id,
        Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Callback<EntityModel, ?> onPeerUnavailable,
        Callback<EntityModel, ?> onMissingResponse,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseRejection,
        Callback<Tuple2<HttpResponseMessage, String>, ?> onInvalidResponseUnknown,
        Function<HttpResponseMessage, Validated<R>> contentParser
    ) {
      this.name = name;
      this.id = id;
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
    public <S> OnFailureStep<I, R> onSuccess(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public OnFailureStep<I, R> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public <S> IsDeliveredStep<I, R> onFailure(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    ) {
      this.onFailure = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsDeliveredStep<I, R> onFailure(EventType<Void, ?> eventType) {
      return onFailure(eventType, _ -> null);
    }

    @Override
    public IsRejectedByInvalidResponseStep<I> isDelivered(Predicate<Tuple2<HttpResponseMessage, R>> isDelivered) {
      this.isDelivered = isDelivered;
      return this;
    }

    @Override
    public RollbackModelStep<I> isRejectedByInvalidResponse(
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
