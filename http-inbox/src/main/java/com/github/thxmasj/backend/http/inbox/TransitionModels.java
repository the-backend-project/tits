package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.BuiltinEventTypes.EventsRolledBack;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.EntitySelector.newEntity;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.BadRequestResponseCreated;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.DuplicateRequestRejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.DuplicatedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.FailedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.InvalidRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIdClaimed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RejectedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestAccepted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestReceived;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejectedAsInvalid;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejectedAsUnauthorized;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejectedAsUnroutable;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRouted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.ResponseCreated;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackMessageIdClaimed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackRequestAccepted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackRequestReceived;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Completed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Dispatched;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Rejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RejectedRollbackFromCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RejectedRollbackFromDispatched;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RolledBack;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RollingBack;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Routed;
import static java.util.stream.Collectors.toSet;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntityModelGroup;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GuardedTransition;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.BadRequest;
import com.github.thxmasj.backend.core.message.http.Created;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseCreator;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.core.message.http.InternalServerError;
import com.github.thxmasj.backend.core.message.http.UnprocessableEntity;
import com.github.thxmasj.backend.http.inbox.Authorization.Refused;
import com.github.thxmasj.backend.http.inbox.HttpInbox.MessageId;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest.Process;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest.Response;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/// The transitions of `RequestRouting` and of the inbox requests
interface TransitionModels {

  static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(List<HttpInboxRequest<?>> requests) {
    var routes = new ArrayList<GuardedTransition<HttpRequestMessage, HttpRequestMessage, ?>>();
    for (HttpInboxRequest<?> request : requests) {
      routes.add(new GuardedTransition<>(request::matches, routedTo(request, false), d -> d));
      if (request.rollback() != null)
        routes.add(new GuardedTransition<>(request::matchesRollback, routedTo(request, true), d -> d));
    }
    return Map.of(
        Begin, List.of(
            onEvent(RequestReceived).to(Routed)
                .assembleInput()
                .choice(routes)
                .otherwise(
                    onEvent(RequestRejectedAsUnroutable).to(Rejected)
                        .assembleInput()
                        .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                        .output(),
                    _ -> "No route matching request"
                )
        ),
        Routed, List.of(
            onEvent(ResponseCreated).to(Completed)
                .assembleInput()
                .output(d -> d)
        ),
        Rejected, List.of(
            onEvent(BadRequestResponseCreated).to(Completed)
                .assemble(c -> c)
                .output(d -> badRequest(d.input(), d.correlationId(), d.timestamp()))
        ),
        Completed, List.of()
    );
  }

  /// Routes a request to a new entity of the inbox request, or a rollback to the entity of the request it rolls back
  private static <T> TransitionModel<HttpRequestMessage, HttpRequestMessage> routedTo(HttpInboxRequest<T> request, boolean rollback) {
    return onEvent(RequestRouted).to(Routed)
        .assembleInput()
        .trigger(rollback ? RollbackRequestReceived : RequestReceived).with(d -> d).on(request)
        .identifiedBy(d -> rollback ? rollbackSelector(request, d) : newEntity())
        .output(Tuple2::t1);
  }

  /// The entity of the request with the message id of the rollback, which is created if there is none, or a new entity
  /// to reject the rollback if it is invalid
  private static <T> EntitySelector rollbackSelector(HttpInboxRequest<T> request, HttpRequestMessage message) {
    var validation = validateRollback(request, message);
    return validation.routedRequest() != null ?
        idEvent(MessageIdClaimed, validation.routedRequest().messageId(), CreateIfNotExists) :
        newEntity();
  }

  /// A received request, which is invalid, refused or routed, to the process with the index if it is a request
  record Validation<T>(String invalidReason, Refused refused, RoutedRequest<T> routedRequest, int process) {

    static <T> Validation<T> invalid(String reason) {
      return new Validation<>(reason, null, null, -1);
    }

    static <T> Validation<T> refusal(Refused refused) {
      return new Validation<>(null, refused, null, -1);
    }

    Dispatch<T> dispatch() {
      return new Dispatch<>(routedRequest, process);
    }

    boolean isInvalid() {
      return invalidReason != null;
    }

    boolean isRefused() {
      return refused != null;
    }

  }

  /// A valid request to dispatch to the process with the index
  record Dispatch<T>(RoutedRequest<T> routedRequest, int process) {}

  /// The index of the first process whose content matches the body, or -1 if none does
  private static <T> int process(HttpInboxRequest<T> request, T content) {
    var processes = request.processes();
    for (int i = 0; i < processes.size(); i++) {
      var predicate = processes.get(i).content();
      if (predicate == null || predicate.predicate().test(content))
        return i;
    }
    return -1;
  }

  private static <T> Validation<T> validate(HttpInboxRequest<T> request, HttpRequestMessage message) {
    Validated<T> body = request.contentParser().apply(message);
    if (body.isInvalid())
      return Validation.invalid("Invalid request body: " + body.invalidReason());
    T content = body.validValue();
    Validated<String> messageIdValue = request.messageIdParser() != null ? request.messageIdParser().apply(message, content) : null;
    if (messageIdValue != null && messageIdValue.isInvalid())
      return Validation.invalid("Unable to parse message id: " + messageIdValue.invalidReason());
    Authorization authorization = request.authorizer() != null ? request.authorizer().apply(message, content) : null;
    if (authorization != null && authorization.isRefused())
      return Validation.refusal(authorization.refused());
    int processIndex = process(request, content);
    if (processIndex < 0)
      return Validation.invalid("No process for the content");
    Process<T, ?> process = request.processes().get(processIndex);
    Validated<? extends EntitySelector> selector = process.selector().apply(message, content);
    if (selector.isInvalid())
      return Validation.invalid("Unable to parse process selector: " + selector.invalidReason());
    String clientId = authorization != null ? authorization.subject() : "N/A";
    EntitySelector processSelector = selector.validValue();
    MessageId messageId = null;
    if (messageIdValue != null) {
      messageId = new MessageId(clientId, messageIdValue.validValue());
    } else {
      // A request without a message id is identified by the event it triggers on an existing entity
      String entity = switch (processSelector) {
        case ById s -> s.id().toString();
        case ByIdEvent<?> s -> s.id().toString();
        case ByIdFromSession _ -> UUID.randomUUID().toString(); // TODO: Use id from session for the process model
        default -> null;
      };
      if (entity != null)
        messageId = new MessageId(clientId, process.eventType().id() + "/" + entity);
    }
    return new Validation<>(
        null,
        null,
        new RoutedRequest<>(message, content, messageId, authorization != null ? authorization.subject() : null, processSelector),
        processIndex
    );
  }

  private static <T> Validation<T> validateRollback(HttpInboxRequest<T> request, HttpRequestMessage message) {
    Validated<String> messageIdValue = request.rollback().messageIdParser().apply(message, null);
    if (messageIdValue.isInvalid())
      return Validation.invalid("Unable to parse message id: " + messageIdValue.invalidReason());
    Validated<String> deduplicationKey = request.rollback().deduplicateOn().apply(message, null);
    if (deduplicationKey.isInvalid())
      return Validation.invalid("Unable to parse deduplication key: " + deduplicationKey.invalidReason());
    // The rollback's own authorizer, as the request's may read the body, which a rollback doesn't have
    var authorizer = request.rollback().authorizer();
    Authorization authorization = authorizer != null ? authorizer.apply(message, null) : null;
    if (authorization != null && authorization.isRefused())
      return Validation.refusal(authorization.refused());
    String subject = authorization != null ? authorization.subject() : null;
    return new Validation<>(
        null,
        null,
        new RoutedRequest<>(
            message,
            null,
            new MessageId(subject != null ? subject : "N/A", messageIdValue.validValue()),
            subject,
            null
        ),
        -1
    );
  }

  static <T> Map<State, List<TransitionModel<?, ?>>> requestTransitions(HttpInboxRequest<T> request) {
    boolean rollback = request.rollback() != null;
    var transitions = new HashMap<State, List<TransitionModel<?, ?>>>();
    transitions.put(Begin, rollback ?
        List.of(receivedTransition(request), rollbackReceivedTransition(request)) :
        List.of(receivedTransition(request))
    );
    var dispatched = new ArrayList<TransitionModel<?, ?>>();
    request.responses().forEach(response -> dispatched.add(customResponse(response)));
    dispatched.addAll(List.of(
        onEvent(RequestCompleted).to(Completed)
            .assemble(c -> tuple(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
            .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1),
        onEvent(InvalidRequestCompleted).to(Completed)
            .assemble(c -> tuple(createResponseMessage(new BadRequest(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
            .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1),
        // The response is 500 Internal Server Error
        onEvent(FailedRequestCompleted).to(Completed).severity(Severity.Error)
            .assemble(c -> tuple(createResponseMessage(new InternalServerError(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
            .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1)
    ));
    if (rollback)
      dispatched.add(rollbackTransition(request, RejectedRollbackFromDispatched));
    transitions.put(Dispatched, dispatched);
    transitions.put(Completed, rollback ? List.of(rollbackTransition(request, RejectedRollbackFromCompleted)) : List.of());
    transitions.put(Rejected, List.of(
        onEvent(RequestCompleted).to(Completed)
            .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
            .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1),
        onEvent(RejectedRequestCompleted).to(Completed)
            .assembleInput()
            .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1),
        onEvent(DuplicatedRequestCompleted).to(Completed)
            .assemble(TransitionContext::input)
            .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
            .output(Tuple2::t1)
    ));
    if (rollback) {
      transitions.put(RollingBack, List.of(
          onEvent(RollbackRequestCompleted).to(RolledBack)
              .assemble(c -> tuple(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
              .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
              .output(Tuple2::t1),
          onEvent(DuplicatedRequestCompleted).to(RolledBack)
              .assemble(TransitionContext::input)
              .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
              .output(Tuple2::t1)
      ));
      transitions.put(RejectedRollbackFromCompleted, List.of(
          onEvent(RollbackRequestCompleted).to(Completed)
              .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
              .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
              .output(Tuple2::t1)
      ));
      transitions.put(RejectedRollbackFromDispatched, List.of(
          onEvent(RollbackRequestCompleted).to(Dispatched)
              .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
              .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
              .output(Tuple2::t1)
      ));
      transitions.put(RolledBack, List.of(repeatedRollbackTransition(request)));
    }
    return Map.copyOf(transitions);
  }

  /// A received request is validated, and dispatched to the application if it is valid
  private static <T> TransitionModel<?, ?> receivedTransition(HttpInboxRequest<T> request) {
    return onEvent(RequestReceived).to(Dispatched)
        .assemble(c -> validate(request, c.input()))
        .when(Validation::isInvalid).then(rejectedAsInvalid(), Validation::invalidReason)
        .when(Validation::isRefused).then(rejectedAsUnauthorized(), Validation::refused)
        .otherwise(dispatchChoiceTransition(request), Validation::dispatch);
  }

  private static TransitionModel<String, HttpResponseMessage> rejectedAsInvalid() {
    return onEvent(RequestRejectedAsInvalid).to(Completed)
        .assemble(c -> badRequest(c.input(), c.correlationId(), c.timestamp()))
        .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static TransitionModel<Refused, HttpResponseMessage> rejectedAsUnauthorized() {
    return onEvent(RequestRejectedAsUnauthorized).to(Completed)
        .assemble(c -> refused(c.input(), c.correlationId(), c.timestamp()))
        .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <D> EventType<D, Void> requestDispatched() {
    return (EventType) HttpInbox.RequestDispatched;
  }

  /// Whether the request is a repeat of the request of the entity which has its message id, accepted or rejected by
  /// the application, so that it gets the same response. An entity of another inbox request has another request, and
  /// one whose request is still being dispatched has no response yet, so the request is no repeat of them.
  private static <T> boolean isSameRequest(HttpRequestMessage newRequest, EventLog existing, HttpInboxRequest<T> request) {
    if (!existing.entityModel().equals(request) || !hasResponse(existing))
      return false;
    return existing.oneIfExists(RequestAccepted).map(Tuple2::t1)
        .or(() -> existing.oneIfExists(RequestRejected))
        .map(original -> request.normalizer().apply(newRequest).equals(request.normalizer().apply(original)))
        .orElse(false);
  }

  private static boolean hasResponse(EventLog log) {
    return log.effectiveEvents().stream().anyMatch(e -> ResponseEventType.outputDataType.equals(e.type().outputDataType()));
  }

  /// Dispatches the request to the process chosen by its content, of several processes
  private static <T> TransitionModel<Dispatch<T>, Void> contentChoiceTransition(HttpInboxRequest<T> request) {
    int last = request.processes().size() - 1;
    var choices = new ArrayList<GuardedTransition<Dispatch<T>, RoutedRequest<T>, ?>>();
    for (int i = 0; i < last; i++) {
      int process = i;
      choices.add(new GuardedTransition<>(d -> d.process() == process, dispatchTransition(request, process), Dispatch::routedRequest));
    }
    return onEvent(TransitionModels.<Dispatch<T>>requestDispatched()).to(Dispatched)
        .assembleInput()
        .choice(choices)
        .otherwise(dispatchTransition(request, last), Dispatch::routedRequest);
  }

  /// The event of the dispatch to the process, named after its content predicate if it has one. It is never stored,
  /// as one of the outcomes of the dispatch always is.
  private static <T> EventType<RoutedRequest<T>, Void> dispatchedTo(HttpInboxRequest<T> request, int process) {
    var content = request.processes().get(process).content();
    if (content == null)
      return requestDispatched();
    return BasicEventType.of(
        "Request dispatched [" + content.description() + "]",
        UUID.nameUUIDFromBytes((request.id() + "/" + process).getBytes(StandardCharsets.UTF_8)),
        DataType.unknown(),
        DataType.none()
    );
  }

  /// Claims the message id of the request, and dispatches it unless the id is taken by an earlier request
  private static <T> TransitionModel<Dispatch<T>, Void> dispatchChoiceTransition(HttpInboxRequest<T> request) {
    var claimed = onEvent(TransitionModels.<Dispatch<T>>requestDispatched()).to(Dispatched)
        .assemble(c -> tuple(c.input(), c.entityId()))
        .trigger(MessageIdClaimed).with(d -> d.t1().routedRequest().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
        .when(d -> d.t2().isIdExists() && d.t2().idExists().log().oneIfExists(RollbackRequestAccepted).isPresent()).then(
            onEvent(RequestRejected).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(RejectedRequestCompleted).with(d -> unprocessableEntity(d.t1(), d.t4(), d.t5())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Rolled back", d.t2().idExists().log().entityId(), d.t1().t1().routedRequest().request())
        )
        .when(d -> d.t2().isIdExists() && isSameRequest(d.t1().t1().routedRequest().request(), d.t2().idExists().log(), request)).then(
            onEvent(DuplicateRequestRejected).to(Rejected)
                .assembleInput()
                .trigger(DuplicatedRequestCompleted).with(Tuple2::t2).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(
                d.t1().t1().routedRequest().request(),
                d.t2().idExists().log().one(ResponseEventType.outputDataType).t1()
            )
        )
        .when(d -> d.t2().isIdExists()).then(
            onEvent(RequestRejected).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(RejectedRequestCompleted).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t2().idExists().log().entityId(), d.t1().t1().routedRequest().request())
        );
    return request.processes().size() == 1 ?
        claimed.otherwise(dispatchTransition(request, 0), d -> d.t1().t1().routedRequest()) :
        claimed.otherwise(contentChoiceTransition(request), d -> d.t1().t1());
  }

  /// Triggers the event of the process of the request on the application
  private static <T, U> TransitionModel<RoutedRequest<T>, Void> dispatchTransition(HttpInboxRequest<T> request, int processIndex) {
    @SuppressWarnings("unchecked")
    Process<T, U> process = (Process<T, U>) request.processes().get(processIndex);
    return onEvent(dispatchedTo(request, processIndex)).to(Dispatched)
        .assembleInput()
        .trigger(process.eventType()).with(process.input()).on(process.entityModel()).identifiedBy(RoutedRequest::processSelector)
        .when(d -> d.t2().isRejected()).then(
            onEvent(RequestRejected).to(Rejected)
                .assembleInput()
                .trigger(RequestCompleted)
                .with(d -> tuple(d.t1(), new EventReference(d.t2(), 0))) // TODO
                .on(request)
                .identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().request())
        )
        .when(d -> d.t2().isUnknownId()).then(
            onEvent(RequestRejected).to(Rejected)
                .assemble(c -> c)
                .trigger(RejectedRequestCompleted).with(d -> badRequest("No such entity", d.correlationId(), d.timestamp())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().input().t3()),
            d -> tuple(d.t2().unknownId().exception().getMessage(), null, d.t1().request())
        )
        .when(_ -> process.eventType() instanceof BasicEventType.Rollback).then(
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .trigger(RequestCompleted).with(d -> tuple("Cancelled", d.t2())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> tuple(d.t1().t1().request(), d.t1().t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        )
        .when(d -> d.t2().isCompleted()).then(
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().completed().entityId(), d.t2().completed().eventNumber()))
        )
        .when(d -> d.t2() instanceof ProcessResult.Pending<?, ?, ?> p && p.exception().entityModel().equals(RequestRouting)).then(
            // TODO: Can immediate reply in router be handled like this?
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().pending().exception().entityId(), 0 /* eventNumber not used in transition anyways */))
        )
        .otherwise(
            // TODO: How to handle unknown process result? ("should never happen")
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        );
  }

  /// A rollback of a request not received: the message id is claimed so that a later request with it is rejected
  private static <T> TransitionModel<?, ?> rollbackReceivedTransition(HttpInboxRequest<T> request) {
    return onEvent(RollbackRequestReceived).to(RollingBack)
        .assemble(c -> validateRollback(request, c.input()))
        .when(Validation::isInvalid).then(rejectedAsInvalid(), Validation::invalidReason)
        .when(Validation::isRefused).then(rejectedAsUnauthorized(), Validation::refused)
        .otherwise(
            onEvent(RollbackRequestAccepted).to(RollingBack)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(MessageIdClaimed).with(d -> d.t1().t2().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(RollbackRequestCompleted)
                .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t3()))
                .on(request)
                .identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t2().request()),
            v -> tuple("Nothing to roll back", v.routedRequest(), null) // TODO: EventReference is null
        );
  }

  /// A rollback of a dispatched or completed request rolls back the events the request caused on the application
  private static <T> TransitionModel<?, ?> rollbackTransition(HttpInboxRequest<T> request, State rejectedState) {
    return onEvent(RollbackRequestReceived).to(RollingBack)
        .assemble((input, log) -> tuple(validateRollback(request, input).routedRequest(), log.entityId(), log.one(RequestAccepted).t2(), log.one(ResponseEventType.outputDataType).t2()))
        .trigger(EventsRolledBack).with(d -> new Data(d.t3().eventNumber() - 1, "HTTP request")).on(request.processes().getFirst().entityModel()).identifiedBy(d -> entityId(d.t3().entityId()))
        .when(d -> d.t2().isRejected()).then(
            onEvent(RequestRejected).to(rejectedState)
                .assembleInput()
                .trigger(RollbackRequestCompleted).with(d -> tuple(d.t1(), new EventReference(d.t2(), 0))).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(RollbackRequestAccepted).to(RollingBack)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(RollbackMessageIdClaimed).with(d -> d.t1().t2().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(RollbackRequestCompleted).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t3())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t2().request()),
            d -> tuple(
                "Rolled back",
                d.t1().t1(),
                new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber())
            )
        )
        .output();
  }

  /// Whether the rollback has the same deduplication key as the first one, which was valid
  private static <T> boolean isSameRollback(HttpInboxRequest<T> request, HttpRequestMessage rollback, HttpRequestMessage first) {
    var deduplicateOn = request.rollback().deduplicateOn();
    return Objects.equals(deduplicateOn.apply(rollback, null).validValue(), deduplicateOn.apply(first, null).validValue());
  }

  /// A rollback repeated after the request is rolled back, or found to have nothing to roll back, gets the response of
  /// the first one
  private static <T> TransitionModel<?, ?> repeatedRollbackTransition(HttpInboxRequest<T> request) {
    return onEvent(RollbackRequestReceived).to(RollingBack)
        .assemble(c -> tuple(
            validateRollback(request, c.input()).routedRequest(),
            c.log().one(RollbackRequestAccepted),
            c.log().one(RollbackRequestCompleted),
            c.log().entityId()
        ))
        // A retry of the first rollback has the same deduplication key
        .when(d -> isSameRollback(request, d.t1().request(), d.t2())).then(
            onEvent(DuplicateRequestRejected).to(RollingBack)
                .assembleInput()
                .trigger(DuplicatedRequestCompleted).with(Tuple2::t2).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(d.t1().request(), d.t3().t1())
        )
        .otherwise(
            onEvent(RequestRejected).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(RejectedRequestCompleted).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t4(), d.t1().request())
        );
  }

  /// Checks that every inbox request takes the responses its own processes may give. From the event of a process on
  /// its entity model, the transitions taken on that event in any state are followed, with all the transitions they may
  /// choose, as which one is taken is decided at runtime. The event types they trigger on the group of inbox requests
  /// are the responses given. The events they trigger at once on other entities, which are calculated in the same
  /// session, are followed in the same way. Delayed and conditional triggers, and actions such as sending an outbox
  /// request, are not followed, as what they lead to is calculated later, so a response given that way is checked when
  /// it is given.
  static void checkResponses(
      List<HttpInboxRequest<?>> requests,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) {
    for (HttpInboxRequest<?> request : requests) {
      Set<UUID> taken = request.transitions().get(Dispatched).stream()
          .map(transition -> transition.eventType().id())
          .collect(toSet());
      for (Process<?, ?> process : request.processes()) {
        for (var given : responsesGiven(new Event(process.entityModel(), process.eventType()), transitions, request)) {
          if (!taken.contains(given.response().id()))
            throw new IllegalArgumentException(String.format(
                "The inbox request %s doesn't take the response %s, which %s on %s triggers on %s, on the way from " +
                    "its process %s on %s. Add it to the request with .response(..)",
                request.name(),
                given.response().name(),
                given.by().eventType().name(),
                given.by().entityModel().name(),
                HttpInbox.Request.name(),
                process.eventType().name(),
                process.entityModel().name()
            ));
        }
      }
    }
  }

  /// An event type on an entity model
  record Event(EntityModel entityModel, EventType<?, ?> eventType) {

    UUID id() {
      return eventType.id();
    }

  }

  /// A response triggered on the group of inbox requests by a transition taken on the event
  record Given(Event by, EventType<?, ?> response) {}

  /// The responses triggered on the request by the transitions taken on the event, and on the events they trigger at
  /// once, except on the inbox's own entity models
  private static List<Given> responsesGiven(
      Event start,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      HttpInboxRequest<?> request
  ) {
    var given = new ArrayList<Given>();
    var visited = new HashSet<List<UUID>>();
    var toVisit = new ArrayDeque<Event>(List.of(start));
    while (!toVisit.isEmpty()) {
      Event event = toVisit.removeFirst();
      if (!visited.add(List.of(event.entityModel().id(), event.id())) || !transitions.containsKey(event.entityModel()))
        continue;
      var taken = transitions.get(event.entityModel()).values().stream()
          .flatMap(List::stream)
          .filter(transition -> transition.eventType().id().equals(event.id()))
          .toList();
      for (var transition : taken) {
        for (var trigger : immediateTriggers(transition)) {
          EntityModel target = trigger.entityModel() != null ? trigger.entityModel() : event.entityModel();
          EventType<?, ?> triggered = trigger.eventSpec().eventType();
          if (target instanceof EntityModelGroup group && group.includes(request))
            given.add(new Given(event, triggered));
          else if (!(target instanceof EntityModelGroup) && !isInboxModel(target))
            toVisit.addLast(new Event(target, triggered));
        }
      }
    }
    return given;
  }

  private static boolean isInboxModel(EntityModel model) {
    return model instanceof HttpInboxRequest<?> || model.equals(RequestRouting) || model.equals(MessageIds);
  }

  /// The triggers of the transition and of the transitions it may choose, which trigger their events at once. A
  /// conditional trigger waits for its condition, and a delayed one isn't among the triggers. The triggers of a
  /// reversal are left out, as it is taken on a later rollback.
  private static List<EventTrigger<?, ?, ?>> immediateTriggers(TransitionModel<?, ?> transition) {
    var triggers = new ArrayList<EventTrigger<?, ?, ?>>();
    for (var trigger : transition.triggers()) {
      if (trigger.condition() == null)
        triggers.add(trigger);
    }
    for (var filter : transition.filters())
      triggers.addAll(immediateTriggers(filter.alternative().model()));
    return triggers;
  }

  private static <I> TransitionModel<?, ?> customResponse(Response<I> response) {
    return onEvent(response.eventType()).to(Completed)
        .assemble(c -> tuple(response.messageCreator().apply(c), c.eventReference()))
        .trigger(ResponseCreated).with(Tuple2::t1).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static HttpResponseMessage badRequest(String detail, String correlationId, ZonedDateTime timestamp) {
    return createResponseMessage(new BadRequest(), null, correlationId, timestamp, detail);
  }

  private static HttpResponseMessage refused(Refused refused, String correlationId, ZonedDateTime timestamp) {
    String reasonPhrase = switch (refused.statusCode()) {
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      default -> "Refused";
    };
    var creator = new HttpResponseCreator(refused.statusCode(), reasonPhrase) {
      @Override
      protected Map<String, String> headers(String correlationId, String data) {
        if (refused.challenge() == null)
          return super.headers(correlationId, data);
        var headers = new HashMap<>(super.headers(correlationId, data));
        headers.put("WWW-Authenticate", refused.challenge());
        return headers;
      }
    };
    return createResponseMessage(creator, null, correlationId, timestamp, refused.reason());
  }

  private static HttpResponseMessage unprocessableEntity(String detail, String correlationId, ZonedDateTime timestamp) {
    return createResponseMessage(new UnprocessableEntity(), null, correlationId, timestamp, detail);
  }

  private static HttpResponseMessage createResponseMessage(
      HttpResponseCreator responseCreator,
      @Nullable EventReference eventReference,
      String correlationId,
      ZonedDateTime timestamp,
      String data
  ) {
    return responseCreator.create(
        data,
        eventReference != null ? eventReference.entityId() : null,
        timestamp,
        correlationId
    );
  }
}
