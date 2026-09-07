package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce.RetryContext;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
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

    MessageCreatorStep id(UUID id);
  }

  public interface MessageCreatorStep {

    default <I> RepeatMessageCreatorStep<I> messageCreator(Function<TransitionContext<I>, HttpRequestMessage> messageCreator) {
      return messageCreatorReactive(messageCreator.andThen(Mono::just));
    }

    <I> RepeatMessageCreatorStep<I> messageCreatorReactive(Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator);
  }

  /// Optional: the repeated message defaults to the message of the original attempt.
  public interface RepeatMessageCreatorStep<I> extends ForwarderStep<I> {

    ForwarderStep<I> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator
    );
  }

  public interface ForwarderStep<I> {

    InflightTimeoutStep<I> forwarder(HttpClient forwarder);
  }

  /// Optional: the inflight timeout defaults to ten seconds.
  public interface InflightTimeoutStep<I> extends ProcessModelStep<I> {

    ProcessModelStep<I> inflightTimeout(Duration inflightTimeout);
  }

  /// Optional: the process model defaults to none, which is sufficient as long as no callback is given.
  public interface ProcessModelStep<I> extends ContentParserStep<I> {

    ContentParserStep<I> processModel(EntityModel processModel);
  }

  public interface ContentParserStep<I> {

    <R> OnSuccessStep<I, R> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser);
  }

  /// Optional: the success callback defaults to none.
  public interface OnSuccessStep<I, R> extends IsDeliveredStep<I, R> {

    <S> IsDeliveredStep<I, R> onSuccess(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    );

    IsDeliveredStep<I, R> onSuccess(EventType<Void, ?> eventType);
  }

  public interface IsDeliveredStep<I, R> {

    IsFailureTransientStep<I, R> isDelivered(Predicate<Tuple2<HttpResponseMessage, R>> isDelivered);
  }

  public interface IsFailureTransientStep<I, R> {

    IsRejectedByInvalidResponseStep<I> isFailureTransient(Predicate<Tuple2<HttpResponseMessage, R>> isFailureTransient);
  }

  public interface IsRejectedByInvalidResponseStep<I> {

    IsFailureByInvalidResponseTransientStep<I> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    );
  }

  public interface IsFailureByInvalidResponseTransientStep<I> {

    IsAttemptAvailableStep<I> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    );
  }

  public interface IsAttemptAvailableStep<I> {

    BackoffAlgorithmStep<I> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable);
  }

  public interface BackoffAlgorithmStep<I> {

    BuildStep<I> backoffAlgorithm(Function<RetryContext, Duration> backoffAlgorithm);
  }

  public interface BuildStep<I> {

    AtLeastOnce<I> build();
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
    public <I> RepeatMessageCreatorStep<I> messageCreatorReactive(
        Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator
    ) {
      return new WithMessageCreator<>(name, id, messageCreator);
    }
  }

  private static final class WithMessageCreator<I> implements RepeatMessageCreatorStep<I>,
      InflightTimeoutStep<I> {

    private final String name;
    private final UUID id;
    private final Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator;
    private BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator =
        (_, message) -> message;
    private HttpClient forwarder;
    private Duration inflightTimeout = Duration.ofSeconds(10);
    private EntityModel processModel;

    private WithMessageCreator(String name, UUID id, Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator) {
      this.name = name;
      this.id = id;
      this.messageCreator = messageCreator;
    }

    @Override
    public ForwarderStep<I> repeatMessageCreator(
        BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator
    ) {
      this.repeatMessageCreator = repeatMessageCreator;
      return this;
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
    public ContentParserStep<I> processModel(EntityModel processModel) {
      this.processModel = processModel;
      return this;
    }

    @Override
    public <R> OnSuccessStep<I, R> contentParser(Function<HttpResponseMessage, Validated<R>> contentParser) {
      return new WithContentParser<>(
          name,
          id,
          messageCreator,
          repeatMessageCreator,
          forwarder,
          inflightTimeout,
          processModel,
          contentParser
      );
    }
  }

  private static final class WithContentParser<I, R> implements OnSuccessStep<I, R>, IsFailureTransientStep<I, R>,
      IsRejectedByInvalidResponseStep<I>, IsFailureByInvalidResponseTransientStep<I>, IsAttemptAvailableStep<I>,
      BackoffAlgorithmStep<I>, BuildStep<I> {

    private final String name;
    private final UUID id;
    private final Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator;
    private final BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator;
    private final HttpClient forwarder;
    private final Duration inflightTimeout;
    private final EntityModel processModel;
    private final Function<HttpResponseMessage, Validated<R>> contentParser;
    private Callback<Tuple2<HttpResponseMessage, R>, ?> onSuccess;
    private Predicate<Tuple2<HttpResponseMessage, R>> isDelivered;
    private Predicate<Tuple2<HttpResponseMessage, R>> isFailureTransient;
    private Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse;
    private Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient;
    private Predicate<RetryContext> isAttemptAvailable;
    private Function<RetryContext, Duration> backoffAlgorithm;

    private WithContentParser(
        String name,
        UUID id,
        Function<TransitionContext<I>, Mono<HttpRequestMessage>> messageCreator,
        BiFunction<TransitionContext<Void>, HttpRequestMessage, HttpRequestMessage> repeatMessageCreator,
        HttpClient forwarder,
        Duration inflightTimeout,
        EntityModel processModel,
        Function<HttpResponseMessage, Validated<R>> contentParser
    ) {
      this.name = name;
      this.id = id;
      this.messageCreator = messageCreator;
      this.repeatMessageCreator = repeatMessageCreator;
      this.forwarder = forwarder;
      this.inflightTimeout = inflightTimeout;
      this.processModel = processModel;
      this.contentParser = contentParser;
    }

    @Override
    public <S> IsDeliveredStep<I, R> onSuccess(
        EventType<S, ?> eventType,
        Function<Tuple2<HttpResponseMessage, R>, S> dataAdapter
    ) {
      this.onSuccess = new Callback<>(eventType, dataAdapter);
      return this;
    }

    @Override
    public IsDeliveredStep<I, R> onSuccess(EventType<Void, ?> eventType) {
      return onSuccess(eventType, _ -> null);
    }

    @Override
    public IsFailureTransientStep<I, R> isDelivered(Predicate<Tuple2<HttpResponseMessage, R>> isDelivered) {
      this.isDelivered = isDelivered;
      return this;
    }

    @Override
    public IsRejectedByInvalidResponseStep<I> isFailureTransient(
        Predicate<Tuple2<HttpResponseMessage, R>> isFailureTransient
    ) {
      this.isFailureTransient = isFailureTransient;
      return this;
    }

    @Override
    public IsFailureByInvalidResponseTransientStep<I> isRejectedByInvalidResponse(
        Predicate<Tuple2<HttpResponseMessage, String>> isRejectedByInvalidResponse
    ) {
      this.isRejectedByInvalidResponse = isRejectedByInvalidResponse;
      return this;
    }

    @Override
    public IsAttemptAvailableStep<I> isFailureByInvalidResponseTransient(
        Predicate<Tuple2<HttpResponseMessage, String>> isFailureByInvalidResponseTransient
    ) {
      this.isFailureByInvalidResponseTransient = isFailureByInvalidResponseTransient;
      return this;
    }

    @Override
    public BackoffAlgorithmStep<I> isAttemptAvailable(Predicate<RetryContext> isAttemptAvailable) {
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
