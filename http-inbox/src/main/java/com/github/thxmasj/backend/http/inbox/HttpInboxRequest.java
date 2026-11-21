package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.core.internal.EventTypes.eventTypesFor;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.Masking;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// An inbox request: an entity model whose entities are the requests of one type the application receives, with a
/// body of type `T`, which trigger an event on an entity of the application. `RequestRouting` routes a received request
/// to the first inbox request matching it, which validates it, dispatches it to the application and waits for the
/// application to respond by triggering a [ResponseEventType] on it (see [HttpInbox#Request]).
///
/// ```java
/// HttpInboxRequest<Order> PlaceOrder = HttpInboxRequest
///     .matching(m -> m.method() == Method.POST && m.uri().getPath().equals("/orders"))
///     .contentParser(HttpInbox.jsonParser(Order.class))
///     .authorizer(HttpInbox.bearerToken(verifier, "orders"))
///     .process(OrderPlaced, Orders, RoutedRequest::body, newEntity())
///     .build("PlaceOrder", UUID.fromString("..."));
/// ```
///
/// The event can depend on the content of the request: with several processes, each chosen by a predicate on the
/// parsed body ([#when(String, Predicate)]), the first one whose predicate matches is taken, and a request matching
/// none is answered with 400 Bad Request:
///
/// ```java
/// HttpInboxRequest<Enrolment> EnrolmentResult = HttpInboxRequest
///     .matching(m -> m.requestLine().matches("POST /enrolments"))
///     .contentParser(HttpInbox.jsonParser(Enrolment.class))
///     .deduplicateOn((_, enrolment) -> valid(enrolment.messageId()))
///     .process(when("status == Accepted", e -> e.status() == ACCEPTED), Activate, Tokens, RoutedRequest::body, tokenOf())
///     .process(when("status == Rejected", e -> e.status() == REJECTED), Reject, Tokens, RoutedRequest::body, tokenOf())
///     .build("EnrolmentResult", UUID.fromString("..."));
/// ```
///
/// @param <T> the type of the body of the request
public final class HttpInboxRequest<T> implements EntityModel {

  private final String name;
  private final UUID id;
  private final Predicate<HttpRequest<byte[]>> matcher;
  private final Function<HttpRequest<byte[]>, Validated<T>> contentParser;
  private final BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn;
  private final BiFunction<HttpRequest<byte[]>, T, Authorization> authorizer;
  private final List<Process<T, ?>> processes;
  private final Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer;
  private final List<Response<?>> responses;
  private final Rollback<?> rollback;
  private final Masking masking;
  private final boolean stored;
  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  private HttpInboxRequest(Builder<T> builder, String name, UUID id) {
    this.name = Objects.requireNonNull(name);
    this.id = Objects.requireNonNull(id);
    this.matcher = builder.matcher;
    this.contentParser = builder.contentParser;
    this.deduplicateOn = builder.deduplicateOn;
    this.authorizer = builder.authorizer;
    this.processes = List.copyOf(builder.processes);
    if (processes.isEmpty())
      throw new IllegalArgumentException("The process of the request " + name + " is missing");
    for (int i = 0; i < processes.size() - 1; i++) {
      if (processes.get(i).content() == null)
        throw new IllegalArgumentException("A process of the request " + name + " without a content predicate must be the last one");
    }
    this.normalizer = builder.normalizer;
    this.responses = List.copyOf(builder.responses);
    this.rollback = builder.rollback;
    if (rollback != null && (rollback.authorizer() == null) != (authorizer == null))
      throw new IllegalArgumentException(authorizer != null ?
          "The rollback of the request " + name + " needs an authorizer, as the message id of a request is the authorized subject's" :
          "The rollback of the request " + name + " can't have an authorizer, as the request has none to give its message id a subject");
    if (rollback != null && processes.stream().map(Process::entityModel).distinct().count() > 1)
      throw new IllegalArgumentException("The processes of the request " + name + ", which can be rolled back, must have the same entity model");
    this.masking = Masking.CREDENTIALS.and(builder.masking);
    this.stored = builder.stored;
    if (deduplicateOn != null && masking.wholeResponseBody())
      throw new IllegalArgumentException("The request " + name + " masks the body of its response, so it can't be deduplicated, as a retry gets the response of the first request as stored");
    if (!stored && deduplicateOn != null)
      throw new IllegalArgumentException("The request " + name + " is not stored, so it can't be deduplicated, as a retry is recognised by the request stored");
    this.transitions = TransitionModels.requestTransitions(this);
  }

  /// Starts building an inbox request taking the requests the matcher matches, e.g. by their method and path
  public static ContentStep matching(Predicate<HttpRequest<byte[]>> matcher) {
    return new ContentStep(Objects.requireNonNull(matcher));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public String toString() {
    return name;
  }

  /// Whether the requests are stored. A request which is not stored, and its `RequestRouting` entity, are entities of
  /// the session only (see [EntityModel#stored()]), while the events its process triggers on the application are
  /// stored if they are of stored types.
  @Override
  public boolean stored() {
    return stored;
  }

  /// What of the requests and responses is masked in the event log, [Masking#CREDENTIALS] and what
  /// [Builder#masking(Masking)] adds
  public Masking masking() {
    return masking;
  }

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  public List<EventType<?, ?>> eventTypes() {
    return transitions.values().stream()
        .flatMap(List::stream)
        .flatMap(t -> eventTypesFor(t).stream())
        .distinct()
        .toList();
  }

  /// Whether the request is one of this inbox request
  public boolean matches(HttpRequest<byte[]> request) {
    return matcher.test(request);
  }

  /// Whether the request is a rollback of a request of this inbox request
  public boolean matchesRollback(HttpRequest<byte[]> request) {
    return rollback != null && rollback.matcher().test(request);
  }

  Function<HttpRequest<byte[]>, Validated<T>> contentParser() {return contentParser;}

  BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn() {return deduplicateOn;}

  BiFunction<HttpRequest<byte[]>, T, Authorization> authorizer() {return authorizer;}

  List<Process<T, ?>> processes() {return processes;}

  Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer() {return normalizer;}

  List<Response<?>> responses() {return responses;}

  Rollback<?> rollback() {return rollback;}

  /// A predicate on the parsed body of a request, choosing the process of the request
  ///
  /// @param description describes the predicate in the diagram of the inbox request, e.g. `status == Accepted`
  public record Content<T>(String description, Predicate<T> predicate) {

    public Content {
      Objects.requireNonNull(description);
      Objects.requireNonNull(predicate);
    }

  }

  /// A predicate on the parsed body of a request, choosing the process of the request
  ///
  /// @param description describes the predicate in the diagram of the inbox request, e.g. `status == Accepted`
  public static <T> Content<T> when(String description, Predicate<T> predicate) {
    return new Content<>(description, predicate);
  }

  /// The event triggered on the application for a request
  ///
  /// @param content chooses the process by the body of the request, or null for any body
  /// @param eventType the event type triggered
  /// @param entityModel the entity model of the entity the event is triggered on
  /// @param input creates the data of the event from the request
  /// @param selector selects the entity the event is triggered on
  record Process<T, U>(
      Content<T> content,
      EventType<U, ?> eventType,
      EntityModel entityModel,
      Function<RoutedRequest<T>, U> input,
      BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
  ) {}

  /// A response the application gives by triggering the event type on the request, created from the event's data
  record Response<I>(ResponseEventType<I> eventType, Function<TransitionContext<I>, HttpResponse<byte[]>> messageCreator) {}

  /// Requests rolling back a request of an inbox request, given to [DeduplicatedBuilder#rollback(Rollback.Builder)].
  /// A rollback is built like the inbox request, with content of a type of its own, and its `deduplicateOn` gives the
  /// message id of the request it rolls back, the same one the `deduplicateOn` of the request gives. The message id may
  /// be in the request line:
  ///
  /// ```java
  /// .rollback(Rollback
  ///     .matching(m -> m.method() == Method.DELETE)
  ///     .noContent()
  ///     .deduplicateOn(parseMessageId("DELETE /orders/(.*)", 1))
  ///     .authorizer(HttpInbox.bearerToken(verifier, "orders")))
  /// ```
  ///
  /// or in the content:
  ///
  /// ```java
  /// .rollback(Rollback
  ///     .matching(m -> m.requestLine().equals("POST /order-reversals"))
  ///     .contentParser(HttpInbox.jsonParser(OrderReversal.class))
  ///     .deduplicateOn((_, reversal) -> valid(reversal.orderId()))
  ///     .authorizer(HttpInbox.bearerToken(verifier, "orders")))
  /// ```
  ///
  /// @param <R> the type of the content of a rollback
  public static final class Rollback<R> {

    private final Predicate<HttpRequest<byte[]>> matcher;
    private final Function<HttpRequest<byte[]>, Validated<R>> contentParser;
    private final BiFunction<HttpRequest<byte[]>, R, Validated<String>> deduplicateOn;
    private final BiFunction<HttpRequest<byte[]>, R, Authorization> authorizer;
    private final Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer;

    private Rollback(Builder<R> builder) {
      this.matcher = builder.matcher;
      this.contentParser = builder.contentParser;
      this.deduplicateOn = builder.deduplicateOn;
      this.authorizer = builder.authorizer;
      this.normalizer = builder.normalizer;
    }

    /// Starts building a rollback taking the requests the matcher matches, e.g. by their method and path
    public static ContentStep matching(Predicate<HttpRequest<byte[]>> matcher) {
      return new ContentStep(Objects.requireNonNull(matcher));
    }

    Predicate<HttpRequest<byte[]>> matcher() {return matcher;}

    Function<HttpRequest<byte[]>, Validated<R>> contentParser() {return contentParser;}

    BiFunction<HttpRequest<byte[]>, R, Validated<String>> deduplicateOn() {return deduplicateOn;}

    BiFunction<HttpRequest<byte[]>, R, Authorization> authorizer() {return authorizer;}

    Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer() {return normalizer;}

    /// The step introducing the type of the content of a rollback
    public static final class ContentStep {

      private final Predicate<HttpRequest<byte[]>> matcher;

      private ContentStep(Predicate<HttpRequest<byte[]>> matcher) {
        this.matcher = matcher;
      }

      /// The content of a rollback is parsed by the parser, and the rollback is answered with 400 Bad Request if it is
      /// invalid
      public <R> DeduplicationStep<R> contentParser(Function<HttpRequest<byte[]>, Validated<R>> contentParser) {
        return new DeduplicationStep<>(matcher, Objects.requireNonNull(contentParser));
      }

      /// The content of a rollback is ignored
      public DeduplicationStep<Void> noContent() {
        return new DeduplicationStep<>(matcher, _ -> valid(null));
      }

    }

    /// The step introducing the message id of the request a rollback rolls back, which every rollback needs
    public static final class DeduplicationStep<R> {

      private final Predicate<HttpRequest<byte[]>> matcher;
      private final Function<HttpRequest<byte[]>, Validated<R>> contentParser;

      private DeduplicationStep(Predicate<HttpRequest<byte[]>> matcher, Function<HttpRequest<byte[]>, Validated<R>> contentParser) {
        this.matcher = matcher;
        this.contentParser = contentParser;
      }

      /// The message id of the request a rollback rolls back, e.g. from its path or its content, the same one the
      /// request's `deduplicateOn` gives. Rollbacks with the same message id are deduplicated like requests: see
      /// [Builder#normalizer(Function)]. A rollback with an invalid message id is answered with 400 Bad Request.
      public Builder<R> deduplicateOn(BiFunction<HttpRequest<byte[]>, R, Validated<String>> deduplicateOn) {
        return new Builder<>(matcher, contentParser, Objects.requireNonNull(deduplicateOn));
      }

    }

    /// Builds a rollback. The authorizer is required if the request has one and not allowed otherwise.
    public static final class Builder<R> {

      private final Predicate<HttpRequest<byte[]>> matcher;
      private final Function<HttpRequest<byte[]>, Validated<R>> contentParser;
      private final BiFunction<HttpRequest<byte[]>, R, Validated<String>> deduplicateOn;
      private BiFunction<HttpRequest<byte[]>, R, Authorization> authorizer;
      private Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer = Function.identity();

      private Builder(
          Predicate<HttpRequest<byte[]>> matcher,
          Function<HttpRequest<byte[]>, Validated<R>> contentParser,
          BiFunction<HttpRequest<byte[]>, R, Validated<String>> deduplicateOn
      ) {
        this.matcher = matcher;
        this.contentParser = contentParser;
        this.deduplicateOn = deduplicateOn;
      }

      /// Authorizes a rollback, which rolls back the request with the message id the same subject sent. The rollback
      /// has an authorizer of its own, as its content is not the request's. A request with an authorizer needs a
      /// rollback with one, and a request without one a rollback without one.
      public Builder<R> authorizer(BiFunction<HttpRequest<byte[]>, R, Authorization> authorizer) {
        this.authorizer = Objects.requireNonNull(authorizer);
        return this;
      }

      /// Normalizes a rollback before it is compared with the first rollback of the request. A rollback equal to the
      /// first one after normalizing is a retry and gets its response, and another is a second rollback, which is
      /// rejected with 400 Bad Request. Without a normalizer, rollbacks are compared as they are, headers and content
      /// included, so a retry must be sent as it was. A normalizer which removes e.g. the date and tracing headers lets
      /// a retry differ in them, and one which keeps e.g. `X-Correlation-Id` tells a second rollback by it.
      public Builder<R> normalizer(Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer);
        return this;
      }

    }

  }

  /// The step introducing the type of the body of the request
  public static final class ContentStep {

    private final Predicate<HttpRequest<byte[]>> matcher;

    private ContentStep(Predicate<HttpRequest<byte[]>> matcher) {
      this.matcher = matcher;
    }

    /// The body of a request is parsed by the parser, and the request is answered with 400 Bad Request if it is invalid
    public <T> Builder<T> contentParser(Function<HttpRequest<byte[]>, Validated<T>> contentParser) {
      return new Builder<>(matcher, Objects.requireNonNull(contentParser));
    }

    /// The body of a request is ignored
    public Builder<Void> noContent() {
      return new Builder<>(matcher, _ -> valid(null));
    }

  }

  /// Builds an inbox request. A process is required, the rest is optional. An inbox request which can be rolled back
  /// needs `deduplicateOn` first, which gives a [DeduplicatedBuilder].
  public static sealed class Builder<T> permits DeduplicatedBuilder {

    private final Predicate<HttpRequest<byte[]>> matcher;
    private final Function<HttpRequest<byte[]>, Validated<T>> contentParser;
    private BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn;
    private BiFunction<HttpRequest<byte[]>, T, Authorization> authorizer;
    private final List<Process<T, ?>> processes = new ArrayList<>();
    private Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer = Function.identity();
    private final List<Response<?>> responses = new ArrayList<>();
    private Rollback<?> rollback;
    private Masking masking = Masking.NONE;
    private boolean stored = true;

    private Builder(Predicate<HttpRequest<byte[]>> matcher, Function<HttpRequest<byte[]>, Validated<T>> contentParser) {
      this.matcher = matcher;
      this.contentParser = contentParser;
    }

    private Builder(Builder<T> other) {
      this.matcher = other.matcher;
      this.contentParser = other.contentParser;
      this.deduplicateOn = other.deduplicateOn;
      this.authorizer = other.authorizer;
      this.processes.addAll(other.processes);
      this.normalizer = other.normalizer;
      this.responses.addAll(other.responses);
      this.masking = other.masking;
      this.stored = other.stored;
    }

    /// The message id of a request, e.g. from its path, by which a retried request is recognised and gets the response
    /// of the first one, and by which a request is rolled back. Without it, requests are not deduplicated: each one is
    /// dispatched to the application, and the request can't be rolled back.
    public DeduplicatedBuilder<T> deduplicateOn(BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn) {
      return new DeduplicatedBuilder<>(this, Objects.requireNonNull(deduplicateOn));
    }

    /// Authorizes a request, e.g. with [HttpInbox#bearerToken(AccessTokenVerifier, String...)]. A refused request is
    /// answered as the [Authorization.Refused] says.
    public Builder<T> authorizer(BiFunction<HttpRequest<byte[]>, T, Authorization> authorizer) {
      this.authorizer = authorizer;
      return this;
    }

    /// The event a request triggers on the application, whatever its content. With processes chosen by the content
    /// before it, it is taken when none of them matches.
    ///
    /// @param eventType the event type triggered
    /// @param entityModel the entity model of the entity the event is triggered on
    /// @param input creates the data of the event from the request
    /// @param selector selects the entity the event is triggered on, e.g. [#newEntity()] or [#parseEntityId(String, int)]
    public <U> Builder<T> process(
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(null, eventType, entityModel, input, selector);
    }

    /// The event a request with the content triggers on the application. The processes are tried in the order they
    /// are given, the first one whose content matches is taken, and a request matching none is answered with 400 Bad
    /// Request. `deduplicateOn`, the authorizer and the normalizer are the same for all of them.
    ///
    /// @param content the predicate on the parsed body choosing the process, see [HttpInboxRequest#when(String, Predicate)]
    /// @param eventType the event type triggered
    /// @param entityModel the entity model of the entity the event is triggered on
    /// @param input creates the data of the event from the request
    /// @param selector selects the entity the event is triggered on, e.g. [#newEntity()] or [#parseEntityId(String, int)]
    public <U> Builder<T> process(
        Content<T> content,
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(Objects.requireNonNull(content), eventType, entityModel, input, selector);
    }

    /// The event, without data, a request triggers on the application, whatever its content. See
    /// [#process(EventType, EntityModel, Function, BiFunction)].
    ///
    /// @param eventType the event type triggered
    /// @param entityModel the entity model of the entity the event is triggered on
    /// @param selector selects the entity the event is triggered on, e.g. [#newEntity()] or [#parseEntityId(String, int)]
    public Builder<T> process(
        EventType<Void, ?> eventType,
        EntityModel entityModel,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(null, eventType, entityModel, _ -> null, selector);
    }

    /// The event, without data, a request with the content triggers on the application. See
    /// [#process(Content, EventType, EntityModel, Function, BiFunction)].
    ///
    /// @param content the predicate on the parsed body choosing the process, see [HttpInboxRequest#when(String, Predicate)]
    /// @param eventType the event type triggered
    /// @param entityModel the entity model of the entity the event is triggered on
    /// @param selector selects the entity the event is triggered on, e.g. [#newEntity()] or [#parseEntityId(String, int)]
    public Builder<T> process(
        Content<T> content,
        EventType<Void, ?> eventType,
        EntityModel entityModel,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(Objects.requireNonNull(content), eventType, entityModel, _ -> null, selector);
    }

    private <U> Builder<T> addProcess(
        Content<T> content,
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      processes.add(new Process<>(
          content,
          Objects.requireNonNull(eventType),
          Objects.requireNonNull(entityModel),
          Objects.requireNonNull(input),
          Objects.requireNonNull(selector)
      ));
      return this;
    }

    /// A response of the application other than those of [HttpInbox#RequestCompleted] and the like, created by the
    /// message creator from the data of the event the application triggers on the request
    public <I> Builder<T> response(
        ResponseEventType<I> eventType,
        Function<TransitionContext<I>, HttpResponse<byte[]>> messageCreator
    ) {
      responses.add(new Response<>(Objects.requireNonNull(eventType), Objects.requireNonNull(messageCreator)));
      return this;
    }

    /// Normalizes a request before it is compared with the first request with the same message id, so that a retried
    /// request is recognised as one even if it differs in e.g. headers
    public Builder<T> normalizer(Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer) {
      this.normalizer = Objects.requireNonNull(normalizer);
      return this;
    }

    /// Masks what the masking gives of the requests and responses in the event log, besides
    /// [Masking#CREDENTIALS], which every inbox request masks. The transitions see the whole request and response.
    ///
    /// A retried request is compared with the first one as stored, so the two are masked before they are compared: a
    /// retry may differ from the first request in what is masked. A retry gets the response of the first one as
    /// stored, so a request which masks the body of its response can't be deduplicated, and a retry whose first
    /// response has a masked header, e.g. a `Set-Cookie`, is answered with 409 Conflict, as it can't be repeated.
    public Builder<T> masking(Masking masking) {
      this.masking = this.masking.and(Objects.requireNonNull(masking));
      return this;
    }

    /// The requests are not stored, nor their `RequestRouting` entities, e.g. requests for static files, health
    /// checks or pages rendered from other entities, or a request with credentials which its process turns into
    /// events of its own. The events the process triggers on the application are stored if they are of stored types.
    /// A request which is not stored can't be deduplicated, so it has no `deduplicateOn`, and the application must
    /// respond in the session of the request, e.g. not by the callback of an outbox request, as the request can't be
    /// found after it.
    public Builder<T> notStored() {
      this.stored = false;
      return this;
    }

    public HttpInboxRequest<T> build(String name, UUID id) {
      return new HttpInboxRequest<>(this, name, id);
    }

  }

  /// Builds an inbox request with `deduplicateOn`, which can be rolled back by its message id
  public static final class DeduplicatedBuilder<T> extends Builder<T> {

    private DeduplicatedBuilder(Builder<T> builder, BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn) {
      super(builder);
      ((Builder<T>) this).deduplicateOn = deduplicateOn;
    }

    @Override
    public DeduplicatedBuilder<T> deduplicateOn(BiFunction<HttpRequest<byte[]>, T, Validated<String>> deduplicateOn) {
      ((Builder<T>) this).deduplicateOn = Objects.requireNonNull(deduplicateOn);
      return this;
    }

    @Override
    public DeduplicatedBuilder<T> authorizer(BiFunction<HttpRequest<byte[]>, T, Authorization> authorizer) {
      super.authorizer(authorizer);
      return this;
    }

    @Override
    public <U> DeduplicatedBuilder<T> process(
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      super.process(eventType, entityModel, input, selector);
      return this;
    }

    @Override
    public <U> DeduplicatedBuilder<T> process(
        Content<T> content,
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      super.process(content, eventType, entityModel, input, selector);
      return this;
    }

    @Override
    public DeduplicatedBuilder<T> process(
        EventType<Void, ?> eventType,
        EntityModel entityModel,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      super.process(eventType, entityModel, selector);
      return this;
    }

    @Override
    public DeduplicatedBuilder<T> process(
        Content<T> content,
        EventType<Void, ?> eventType,
        EntityModel entityModel,
        BiFunction<HttpRequest<byte[]>, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      super.process(content, eventType, entityModel, selector);
      return this;
    }

    @Override
    public <I> DeduplicatedBuilder<T> response(
        ResponseEventType<I> eventType,
        Function<TransitionContext<I>, HttpResponse<byte[]>> messageCreator
    ) {
      super.response(eventType, messageCreator);
      return this;
    }

    @Override
    public DeduplicatedBuilder<T> normalizer(Function<HttpRequest<byte[]>, HttpRequest<byte[]>> normalizer) {
      super.normalizer(normalizer);
      return this;
    }

    @Override
    public DeduplicatedBuilder<T> masking(Masking masking) {
      super.masking(masking);
      return this;
    }

    /// Requests the rollback matches roll back the request with the message id its `deduplicateOn` gives, by rolling
    /// back the events the request caused on the application. A rollback of a request not received yet makes a later
    /// request with the message id rejected. The processes of a request which can be rolled back must have the same
    /// entity model. See [Rollback].
    public DeduplicatedBuilder<T> rollback(Rollback.Builder<?> rollback) {
      ((Builder<T>) this).rollback = new Rollback<>(Objects.requireNonNull(rollback));
      return this;
    }

  }

  /// The existing entity with the id. An entity can't be created with an id from a request, as it would not be
  /// time-ordered.
  public static Validated<EntitySelector> entityId(String s) {
    if (s == null)
      return invalid("Entity id is missing");
    UUID uuid;
    try {
      uuid = UUID.fromString(s);
    } catch (IllegalArgumentException e) {
      return invalid("Entity id is invalid: " + e.getMessage());
    }
    return valid(EntitySelector.entityId(uuid));
  }

  /// The existing entity with the id
  public static Validated<EntitySelector> entityId(UUID uuid) {
    if (uuid == null)
      return invalid("Entity id is missing");
    return valid(EntitySelector.entityId(uuid));
  }

  /// A new entity for every request
  public static <T> BiFunction<HttpRequest<byte[]>, T, Validated<EntitySelector>> newEntity() {
    return (_, _) -> valid(EntitySelector.newEntity());
  }

  /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
  /// it would not be time-ordered.
  public static <T> BiFunction<HttpRequest<byte[]>, T, Validated<EntitySelector>> parseEntityId(
      String requestLinePattern,
      int captureGroup
  ) {
    return parseEntityId(Pattern.compile(requestLinePattern), captureGroup);
  }

  /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
  /// it would not be time-ordered.
  public static <T> BiFunction<HttpRequest<byte[]>, T, Validated<EntitySelector>> parseEntityId(
      Pattern requestLinePattern,
      int captureGroup
  ) {
    return (request, _) -> {
      Matcher matcher = requestLinePattern.matcher(request.requestLine());
      String s = matcher.find() ? matcher.group(captureGroup) : null;
      if (s == null)
        return invalid("Unable to extract entity id from request line");
      try {
        return valid(EntitySelector.entityId(UUID.fromString(s)));
      } catch (IllegalArgumentException e) {
        return invalid("Entity id is not a valid UUID");
      }
    };
  }

  /// The message id in the request line
  public static <T> BiFunction<HttpRequest<byte[]>, T, Validated<String>> parseMessageId(
      String requestLinePattern,
      int captureGroup
  ) {
    return parseMessageId(Pattern.compile(requestLinePattern), captureGroup);
  }

  /// The message id in the request line
  public static <T> BiFunction<HttpRequest<byte[]>, T, Validated<String>> parseMessageId(
      Pattern requestLinePattern,
      int captureGroup
  ) {
    return (request, _) -> {
      Matcher matcher = requestLinePattern.matcher(request.requestLine());
      String s = matcher.find() ? matcher.group(captureGroup) : null;
      if (s == null)
        return invalid("Unable to extract message id from request line");
      return valid(s);
    };
  }

}
