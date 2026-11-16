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

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EventReference;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /// A received request, which is invalid, refused or routed
  record Validation<T>(String invalidReason, Refused refused, RoutedRequest<T> routedRequest) {

    static <T> Validation<T> invalid(String reason) {
      return new Validation<>(reason, null, null);
    }

    static <T> Validation<T> refusal(Refused refused) {
      return new Validation<>(null, refused, null);
    }

    boolean isInvalid() {
      return invalidReason != null;
    }

    boolean isRefused() {
      return refused != null;
    }

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
    Validated<? extends EntitySelector> selector = request.process().selector().apply(message, content);
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
        messageId = new MessageId(clientId, request.process().eventType().id() + "/" + entity);
    }
    return new Validation<>(
        null,
        null,
        new RoutedRequest<>(message, content, messageId, authorization != null ? authorization.subject() : null, processSelector)
    );
  }

  private static <T> Validation<T> validateRollback(HttpInboxRequest<T> request, HttpRequestMessage message) {
    Validated<String> messageIdValue = request.rollback().messageIdParser().apply(message, null);
    if (messageIdValue.isInvalid())
      return Validation.invalid("Unable to parse message id: " + messageIdValue.invalidReason());
    Authorization authorization = request.authorizer() != null ? request.authorizer().apply(message, null) : null;
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
        )
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
        .otherwise(dispatchChoiceTransition(request), Validation::routedRequest);
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
  private static <T> EventType<RoutedRequest<T>, Void> requestDispatched() {
    return (EventType) HttpInbox.RequestDispatched;
  }

  private static <T> boolean isSameRequest(HttpRequestMessage newRequest, HttpRequestMessage oldRequest, HttpInboxRequest<T> request) {
    return request.normalizer().apply(newRequest).equals(request.normalizer().apply(oldRequest));
  }

  /// Claims the message id of the request, and dispatches it unless the id is taken by an earlier request
  private static <T> TransitionModel<RoutedRequest<T>, Void> dispatchChoiceTransition(HttpInboxRequest<T> request) {
    return onEvent(TransitionModels.<T>requestDispatched()).to(Dispatched)
        .assemble(c -> tuple(c.input(), c.entityId()))
        .trigger(MessageIdClaimed).with(d -> d.t1().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
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
            d -> tuple("Rolled back", d.t2().idExists().log().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isIdExists() && isSameRequest(d.t1().t1().request(), d.t2().idExists().log().one(RequestAccepted).t1(), request)).then(
            onEvent(DuplicateRequestRejected).to(Rejected)
                .assembleInput()
                .trigger(DuplicatedRequestCompleted).with(Tuple2::t2).on(request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(
                d.t1().t1().request(),
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
            d -> tuple("Conflict", d.t2().idExists().log().entityId(), d.t1().t1().request())
        )
        .otherwise(dispatchTransition(request), d -> d.t1().t1());
  }

  /// Triggers the event of the request on the application
  private static <T, U> TransitionModel<RoutedRequest<T>, Void> dispatchTransition(HttpInboxRequest<T> request) {
    @SuppressWarnings("unchecked")
    Process<T, U> process = (Process<T, U>) request.process();
    return onEvent(TransitionModels.<T>requestDispatched()).to(Dispatched)
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
        .trigger(EventsRolledBack).with(d -> new Data(d.t3().eventNumber() - 1, "HTTP request")).on(request.process().entityModel()).identifiedBy(d -> entityId(d.t3().entityId()))
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

  /// A rollback repeated after the request is rolled back gets the response of the first one
  private static <T> TransitionModel<?, ?> repeatedRollbackTransition(HttpInboxRequest<T> request) {
    return onEvent(RollbackRequestReceived).to(RollingBack)
        .assemble(c -> tuple(
            validateRollback(request, c.input()).routedRequest(),
            c.log().one(RollbackRequestAccepted),
            c.log().one(RollbackRequestCompleted),
            c.log().entityId()
        ))
        .when(d -> d.t1().request().equals(request.normalizer().apply(d.t2()))).then(
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
