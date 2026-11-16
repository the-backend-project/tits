package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.core.database.Mappers.eventTypesFor;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
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
///     .messageIdParser((_, enrolment) -> valid(enrolment.messageId()))
///     .process(when("status == Accepted", e -> e.status() == ACCEPTED), Activate, Tokens, RoutedRequest::body, tokenOf())
///     .process(when("status == Rejected", e -> e.status() == REJECTED), Reject, Tokens, RoutedRequest::body, tokenOf())
///     .build("EnrolmentResult", UUID.fromString("..."));
/// ```
///
/// @param <T> the type of the body of the request
public final class HttpInboxRequest<T> implements EntityModel {

  private final String name;
  private final UUID id;
  private final Predicate<HttpRequestMessage> matcher;
  private final Function<HttpRequestMessage, Validated<T>> contentParser;
  private final BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser;
  private final BiFunction<HttpRequestMessage, T, Authorization> authorizer;
  private final List<Process<T, ?>> processes;
  private final Function<HttpRequestMessage, HttpRequestMessage> normalizer;
  private final List<Response<?>> responses;
  private final Rollback rollback;
  private final Map<State, List<TransitionModel<?, ?>>> transitions;

  private HttpInboxRequest(Builder<T> builder, String name, UUID id) {
    this.name = Objects.requireNonNull(name);
    this.id = Objects.requireNonNull(id);
    this.matcher = builder.matcher;
    this.contentParser = builder.contentParser;
    this.messageIdParser = builder.messageIdParser;
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
    this.transitions = TransitionModels.requestTransitions(this);
  }

  /// Starts building an inbox request taking the requests the matcher matches, e.g. by their method and path
  public static ContentStep matching(Predicate<HttpRequestMessage> matcher) {
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
  public boolean matches(HttpRequestMessage request) {
    return matcher.test(request);
  }

  /// Whether the request is a rollback of a request of this inbox request
  public boolean matchesRollback(HttpRequestMessage request) {
    return rollback != null && rollback.matcher().test(request);
  }

  Function<HttpRequestMessage, Validated<T>> contentParser() {return contentParser;}

  BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser() {return messageIdParser;}

  BiFunction<HttpRequestMessage, T, Authorization> authorizer() {return authorizer;}

  List<Process<T, ?>> processes() {return processes;}

  Function<HttpRequestMessage, HttpRequestMessage> normalizer() {return normalizer;}

  List<Response<?>> responses() {return responses;}

  Rollback rollback() {return rollback;}

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
      BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
  ) {}

  /// A response the application gives by triggering the event type on the request, created from the event's data
  record Response<I>(ResponseEventType<I> eventType, Function<TransitionContext<I>, HttpResponseMessage> messageCreator) {}

  /// A rollback of a request, identified by its message id
  record Rollback(
      Predicate<HttpRequestMessage> matcher,
      BiFunction<HttpRequestMessage, Void, Validated<String>> messageIdParser,
      BiFunction<HttpRequestMessage, Void, Authorization> authorizer
  ) {}

  /// The step introducing the type of the body of the request
  public static final class ContentStep {

    private final Predicate<HttpRequestMessage> matcher;

    private ContentStep(Predicate<HttpRequestMessage> matcher) {
      this.matcher = matcher;
    }

    /// The body of a request is parsed by the parser, and the request is answered with 400 Bad Request if it is invalid
    public <T> Builder<T> contentParser(Function<HttpRequestMessage, Validated<T>> contentParser) {
      return new Builder<>(matcher, Objects.requireNonNull(contentParser));
    }

    /// The body of a request is ignored
    public Builder<Void> noContent() {
      return new Builder<>(matcher, _ -> valid(null));
    }

  }

  /// Builds an inbox request. A process is required, the rest is optional.
  public static final class Builder<T> {

    private final Predicate<HttpRequestMessage> matcher;
    private final Function<HttpRequestMessage, Validated<T>> contentParser;
    private BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser;
    private BiFunction<HttpRequestMessage, T, Authorization> authorizer;
    private final List<Process<T, ?>> processes = new ArrayList<>();
    private Function<HttpRequestMessage, HttpRequestMessage> normalizer = Function.identity();
    private final List<Response<?>> responses = new ArrayList<>();
    private Rollback rollback;

    private Builder(Predicate<HttpRequestMessage> matcher, Function<HttpRequestMessage, Validated<T>> contentParser) {
      this.matcher = matcher;
      this.contentParser = contentParser;
    }

    /// The id of a request, e.g. from its path, by which a retried request is recognised and gets the response of the
    /// first one, and by which a request is rolled back. Without it, a request selecting an existing entity of the
    /// application is identified by the event type and the entity.
    public Builder<T> messageIdParser(BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser) {
      this.messageIdParser = messageIdParser;
      return this;
    }

    /// Authorizes a request, e.g. with [HttpInbox#bearerToken(AccessTokenVerifier, String...)]. A refused request is
    /// answered as the [Authorization.Refused] says.
    public Builder<T> authorizer(BiFunction<HttpRequestMessage, T, Authorization> authorizer) {
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
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(null, eventType, entityModel, input, selector);
    }

    /// The event a request with the content triggers on the application. The processes are tried in the order they
    /// are given, the first one whose content matches is taken, and a request matching none is answered with 400 Bad
    /// Request. The message id parser, the authorizer and the normalizer are the same for all of them.
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
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
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
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
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
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
    ) {
      return addProcess(Objects.requireNonNull(content), eventType, entityModel, _ -> null, selector);
    }

    private <U> Builder<T> addProcess(
        Content<T> content,
        EventType<U, ?> eventType,
        EntityModel entityModel,
        Function<RoutedRequest<T>, U> input,
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> selector
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
        Function<TransitionContext<I>, HttpResponseMessage> messageCreator
    ) {
      responses.add(new Response<>(Objects.requireNonNull(eventType), Objects.requireNonNull(messageCreator)));
      return this;
    }

    /// Normalizes a request before it is compared with the first request with the same message id, so that a retried
    /// request is recognised as one even if it differs in e.g. headers
    public Builder<T> normalizer(Function<HttpRequestMessage, HttpRequestMessage> normalizer) {
      this.normalizer = Objects.requireNonNull(normalizer);
      return this;
    }

    /// Requests the matcher matches roll back the request with the message id the parser gives, by rolling back the
    /// events the request caused on the application. A rollback of a request not received yet makes a later request
    /// with the message id rejected. The processes of a request which can be rolled back must have the same entity
    /// model. This is for a request without an authorizer; one with an authorizer needs a rollback with an authorizer
    /// too.
    public Builder<T> rollback(
        Predicate<HttpRequestMessage> matcher,
        BiFunction<HttpRequestMessage, Void, Validated<String>> messageIdParser
    ) {
      this.rollback = new Rollback(Objects.requireNonNull(matcher), Objects.requireNonNull(messageIdParser), null);
      return this;
    }

    /// Requests the matcher matches, authorized by the authorizer, roll back the request with the message id the
    /// parser gives, which the same subject sent. A rollback has no body to authorize it by, so the authorizer of the
    /// request isn't used: it may read the body, which a rollback doesn't have. See [#rollback(Predicate, BiFunction)].
    public Builder<T> rollback(
        Predicate<HttpRequestMessage> matcher,
        BiFunction<HttpRequestMessage, Void, Validated<String>> messageIdParser,
        BiFunction<HttpRequestMessage, Void, Authorization> authorizer
    ) {
      this.rollback = new Rollback(
          Objects.requireNonNull(matcher),
          Objects.requireNonNull(messageIdParser),
          Objects.requireNonNull(authorizer)
      );
      return this;
    }

    public HttpInboxRequest<T> build(String name, UUID id) {
      return new HttpInboxRequest<>(this, name, id);
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
  public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> newEntity() {
    return (_, _) -> valid(EntitySelector.newEntity());
  }

  /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
  /// it would not be time-ordered.
  public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> parseEntityId(
      String requestLinePattern,
      int captureGroup
  ) {
    return parseEntityId(Pattern.compile(requestLinePattern), captureGroup);
  }

  /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
  /// it would not be time-ordered.
  public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> parseEntityId(
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
  public static <T> BiFunction<HttpRequestMessage, T, Validated<String>> parseMessageId(
      String requestLinePattern,
      int captureGroup
  ) {
    return parseMessageId(Pattern.compile(requestLinePattern), captureGroup);
  }

  /// The message id in the request line
  public static <T> BiFunction<HttpRequestMessage, T, Validated<String>> parseMessageId(
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
