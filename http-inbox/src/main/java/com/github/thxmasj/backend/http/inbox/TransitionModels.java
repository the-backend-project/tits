package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.BuiltinEventTypes.EventsRolledBack;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestAccepted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackRequestAccepted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.DuplicatedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.FailedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.InvalidRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RejectedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIdClaimed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejectedAsUnauthorized;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejectedAsUnroutable;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.DuplicateRequestRejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.ResponseCreated;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.BadRequestResponseCreated;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RefusalResponseCreated;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RollbackMessageIdClaimed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestReceived;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Completed;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Dispatched;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Rejected;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RejectedRollbackFromCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RejectedRollbackFromDispatched;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RolledBack;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.RollingBack;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.States.Routed;
import static java.util.stream.Collectors.toList;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EventReference;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.GuardedTransition;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.inbox.Authorization.Refused;
import com.github.thxmasj.backend.http.inbox.HttpInbox.CustomResponse;
import com.github.thxmasj.backend.http.inbox.HttpInbox.MessageId;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RouteId;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.backend.core.message.http.BadRequest;
import com.github.thxmasj.backend.core.message.http.Created;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseCreator;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.core.message.http.InternalServerError;
import com.github.thxmasj.backend.core.message.http.UnprocessableEntity;
import jakarta.annotation.Nullable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

public interface TransitionModels {

  static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(List<HttpRequestRoute<?>> routes) {
    return Map.of(
        Begin, List.of(
            onEvent(RequestReceived).to(Routed)
                .assembleInput()
                .choice(IntStream.range(0, routes.size()).mapToObj(i -> TransitionModels.metadataRouteTransition(i, routes.get(i))).collect(toList()))
                .otherwise(
                    onEvent(RequestRejectedAsUnroutable).to(Rejected)
                        .assembleInput()
                        .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                        .output(),
                    _ -> "No route matching request metadata"
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
                .output(d -> badRequest(d.input(), d.correlationId(), d.timestamp())),
            onEvent(RefusalResponseCreated).to(Completed)
                .assemble(c -> c)
                .output(d -> refused(d.input(), d.correlationId(), d.timestamp()))
        ),
        Completed, List.of()
    );
  }

  private static <T> GuardedTransition<HttpRequestMessage, HttpRequestMessage, Void> metadataRouteTransition(
      int metaDataRouteId,
      HttpRequestRoute<T> metadataRoute
  ) {
    return new GuardedTransition<>(
        metadataRoute.metadataPredicate(),
        onEvent(RequestReceived).to(Routed)
            .assemble(c -> tuple(
                    c.input(),
                    metadataRoute.contentParser() != null ? metadataRoute.contentParser().apply(c.input()) : null
                )
            )
            .when(d -> d.t2() != null && d.t2().isInvalid()).then(
                onEvent(RequestRejectedAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Invalid request body: " + d.t2().invalidReason()
            )
            .choice(
                IntStream.range(0, metadataRoute.contentRoutes().size())
                    .mapToObj(contentRouteId -> contentRouteTransition(
                        new RouteId(metaDataRouteId, contentRouteId),
                        metadataRoute.contentRoutes().get(contentRouteId),
                        d -> tuple(d.t1(), d.t2().validValue())
                    ))
                    .collect(toList())
            )
            .otherwise(
                onEvent(RequestRejectedAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                _ -> "No route matching content"
            ),
        Function.identity()
    );
  }

  private static <T, U> GuardedTransition<Tuple2<HttpRequestMessage, Validated<T>>, Tuple2<HttpRequestMessage, T>, Void> contentRouteTransition(
      RouteId routeId,
      ContentRoute<T, U> contentRoute,
      Function<Tuple2<HttpRequestMessage, Validated<T>>, Tuple2<HttpRequestMessage, T>> dataAdapter
  ) {
    EventType<Tuple2<HttpRequestMessage, T>, Void> contentRoutingEvent = BasicEventType.of(
        "Request routed: " + contentRoute.predicateName(),
        contentRoute.dispatchingEventTypeId(),
        DataType.unknown(),
        DataType.none()
    );
    return new GuardedTransition<>(
        d -> d.t2().isValid() && contentRoute.predicate().test(d.t2().validValue()),
        onEvent(contentRoutingEvent).to(Routed)
            .assemble(d -> tuple(
                d.input().t1(),
                d.input().t2(),
                contentRoute.messageIdParser() != null ? contentRoute.messageIdParser().apply(d.input().t1(), d.input().t2()) : null,
                contentRoute.authorizer() != null ? contentRoute.authorizer().apply(d.input().t1(), d.input().t2()) : null,
                contentRoute.processSelector() != null ? contentRoute.processSelector().apply(d.input().t1(), d.input().t2()) : null
            ))
            .when(d -> d.t3() != null && d.t3().isInvalid()).then(
                onEvent(RequestRejectedAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Unable to parse message id: " + d.t3().invalidReason()
            )
            .when(d -> d.t4() != null && d.t4().isRefused()).then(
                onEvent(RequestRejectedAsUnauthorized).to(Rejected)
                    .assembleInput()
                    .trigger(RefusalResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> d.t4().refused()
            )
            .when(d -> d.t5() != null && d.t5().isInvalid()).then(
                onEvent(RequestRejectedAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(BadRequestResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Unable to parse process selector: " + d.t5().invalidReason()
            )
            .otherwise(
                onEvent(contentRoute.dispatchingEventType()).to(Routed)
                    .assembleInput()
                    .trigger(contentRoute.dispatchingEventType()).with(d -> d).on(RequestDispatching)
                    .identifiedBy(d ->
                        contentRoute.isRollback() ?
                            idEvent(MessageIdClaimed, d.messageId(), CreateIfNotExists) :
                            new ById(UUID.randomUUID(), AlwaysCreate)
                    )
                    .output(),
                d -> {
                  String clientId = d.t4() != null ? d.t4().subject() : "N/A";
                  String messageIdValue = d.t3() != null ? d.t3().validValue() : null;
                  EntitySelector processSelector = d.t5() != null ? d.t5().validValue() : null;
                  MessageId messageId = null;
                  if (messageIdValue != null) {
                    messageId = new MessageId(clientId, messageIdValue);
                  } else if (processSelector != null) {
                    messageId = new MessageId(
                        clientId,
                        contentRoute.processEventType().id() + "/" + switch (processSelector) {
                          case ById s -> s.id().toString();
                          case ByIdEvent<?> s -> s.id().toString();
                          case ByIdFromSession _ -> UUID.randomUUID().toString(); // TODO: Use id from session for route.processType().
                          // TODO: ByLastIndexed not handled
                          default -> throw new IllegalStateException("Process selector type not handled: " + processSelector.getClass().getName());
                        }
                    );
                  }
                  return new RoutedRequest<>(
                      routeId,
                      d.t1(),
                      d.t2(),
                      messageId,
                      d.t4() != null ? d.t4().subject() : null,
                      processSelector
                  );
                }
            ),
        dataAdapter
    );
  }

  private static <T, U> boolean compareRequests(HttpRequestMessage newRequest, HttpRequestMessage oldRequest, ContentRoute<T, U> route) {
    return route.normalizer().apply(newRequest).equals(route.normalizer().apply(oldRequest));
  }

  private static <T, U> TransitionModel<?, ?> dispatchChoiceTransition(ContentRoute<T, U> route) {
    return onEvent(route.dispatchingEventType()).to(Dispatched)
        .assemble(c -> tuple(c.input(), c.log().entityId()))
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
                .trigger(RejectedRequestCompleted).with(d -> unprocessableEntity(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Rolled back", d.t2().idExists().log().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isIdExists() && compareRequests(d.t1().t1().request(), d.t2().idExists().log().one(RequestAccepted).t1(), route)).then(
            onEvent(DuplicateRequestRejected).to(Rejected)
                .assembleInput()
                .trigger(DuplicatedRequestCompleted).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
                .trigger(RejectedRequestCompleted).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t2().idExists().log().entityId(), d.t1().t1().request())
        )
        .otherwise(dispatchTransition(route), d -> d.t1().t1());
  }

  private static <T, U> TransitionModel<RoutedRequest<T>, Void> dispatchTransition(ContentRoute<T, U> route) {
    return onEvent(route.dispatchingEventType()).to(Dispatched)
        .assembleInput()
        .trigger(route.processEventType()).with(route.processInput()).on(route.processType()).identifiedBy(
            RoutedRequest::processSelector)
        .when(d -> d.t2().isRejected()).then(
            onEvent(RequestRejected).to(Rejected)
                .assembleInput()
                .trigger(RequestCompleted)
                .with(d -> tuple(d.t1(), new EventReference(d.t2(), 0))) // TODO
                .on(RequestDispatching)
                .identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().request())
        )
        .when(d -> d.t2().isUnknownId()).then(
            onEvent(RequestRejected).to(Rejected)
                .assemble(c -> c)
                .trigger(RejectedRequestCompleted).with(d -> badRequest("No such entity", d.correlationId(), d.timestamp())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().input().t3()),
            d -> tuple(d.t2().unknownId().exception().getMessage(), null, d.t1().request())
        )
        .when(_ -> route.processEventType() instanceof BasicEventType.Rollback).then(
            onEvent(RequestAccepted).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .trigger(RequestCompleted).with(d -> tuple("Cancelled", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
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

  private static <T, U> TransitionModel<?, ?> triggerRollbackTransition(ContentRoute<T, U> route, State rejectedState) {
    return onEvent(route.dispatchingEventType()).to(RollingBack)
        .assemble((input, log) -> tuple(input, log.entityId(), log.one(RequestAccepted).t2(), log.one(ResponseEventType.outputDataType).t2()))
        .trigger(EventsRolledBack).with(d -> new Data(d.t3().eventNumber() - 1, "HTTP request")).on(route.processType()).identifiedBy(d -> entityId(d.t3().entityId()))
        .when(d -> d.t2().isRejected()).then(
            onEvent(RequestRejected).to(rejectedState)
                .assembleInput()
                .trigger(RollbackRequestCompleted).with(d -> tuple(d.t1(), new EventReference(d.t2(), 0))).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(RollbackRequestAccepted).to(RollingBack)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(RollbackMessageIdClaimed).with(d -> d.t1().t2().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(RollbackRequestCompleted).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t3())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t2().request()),
            d -> tuple(
                "Rolled back",
                d.t1().t1(),
                new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber())
            )
        )
        .output();
  }

  static Map<State, List<TransitionModel<?, ?>>> requestDispatchingTransitions(
      List<HttpRequestRoute<?>> routes,
      List<CustomResponse<?>> customResponses
  ) {
    return Map.of(
        Begin, join(
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(route -> !route.isRollback())
                .map(route -> TransitionModels.dispatchChoiceTransition(route))
                .collect(toList()),
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(ContentRoute::isRollback)
                .map(route ->
                    onEvent(route.dispatchingEventType()).to(RollingBack)
                        .assemble(c -> tuple(c.input(), c.log().entityId()))
                        .otherwise(
                            onEvent(RollbackRequestAccepted).to(RollingBack)
                                .assemble(c -> tuple(c.input(), c.log().entityId()))
                                .trigger(MessageIdClaimed).with(d -> d.t1().t2().messageId()).on(MessageIds).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                                .trigger(RollbackRequestCompleted)
                                .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t3()))
                                .on(RequestDispatching)
                                .identifiedBy(entityIdFromSession())
                                .output(d -> d.t1().t1().t1().t2().request()),
                            d -> tuple("Nothing to roll back", d.t1(), null) // TODO: EventReference is null
                        )
                ).collect(toList())
        ),
        Dispatched, join(
            customResponses.stream().map(TransitionModels::customResponse).collect(toList()),
            List.of(
                onEvent(RequestCompleted).to(Completed)
                    .assemble(c -> tuple(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                onEvent(InvalidRequestCompleted).to(Completed)
                    .assemble(c -> tuple(createResponseMessage(new BadRequest(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                onEvent(FailedRequestCompleted).to(Completed)
                    .assemble(c -> tuple(createResponseMessage(new InternalServerError(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1)
            ),
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(ContentRoute::isRollback)
                .map(r -> triggerRollbackTransition(r, RejectedRollbackFromDispatched))
                .collect(toList())
        ),
        Completed, routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(ContentRoute::isRollback)
            .map(route -> triggerRollbackTransition(route, RejectedRollbackFromCompleted))
            .collect(toList()),
        RollingBack, List.of(
            onEvent(RollbackRequestCompleted).to(RolledBack)
                .assemble(c -> tuple(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(DuplicatedRequestCompleted).to(RolledBack)
                .assemble(TransitionContext::input)
                .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        Rejected, List.of(
            onEvent(RequestCompleted).to(Completed)
                .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(RejectedRequestCompleted).to(Completed)
                .assembleInput()
                .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(DuplicatedRequestCompleted).to(Completed)
                .assemble(TransitionContext::input)
                .trigger(ResponseCreated).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromCompleted, List.of(
            onEvent(RollbackRequestCompleted).to(Completed)
                .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromDispatched, List.of(
            onEvent(RollbackRequestCompleted).to(Dispatched)
                .assemble(c -> tuple(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RolledBack,
        // For handling rollback repeats properly
        routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(ContentRoute::isRollback)
            .map(route ->
                onEvent(route.dispatchingEventType()).to(RollingBack)
                    .assemble(c -> tuple(c.input(), c.log().one(RollbackRequestAccepted), c.log().one(
                        RollbackRequestCompleted), c.log().entityId()))
                    .when(d -> d.t1().request().equals(route.normalizer().apply(d.t2()))).then(
                        onEvent(DuplicateRequestRejected).to(RollingBack)
                            .assembleInput()
                            .trigger(DuplicatedRequestCompleted).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
                            .trigger(RejectedRequestCompleted).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t3()),
                        d -> tuple("Conflict", d.t4(), d.t1().request())
                    )
            ).collect(toList())
    );
  }

  @SafeVarargs
  private static List<TransitionModel<?, ?>> join(List<TransitionModel<?, ?>>... lists) {
    var result = new ArrayList<TransitionModel<?, ?>>();
    for (var list : lists) {
      result.addAll(list);
    }
    return result;
  }


  private static <I> TransitionModel<?, ?> customResponse(CustomResponse<I> customResponse) {
    return onEvent(customResponse.event()).to(Completed)
        .assemble(c -> tuple(customResponse.messageCreator().apply(c), c.eventReference()))
        .trigger(ResponseCreated).with(d -> d.t1()).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static HttpResponseMessage badRequest(
      String detail,
      String correlationId,
      ZonedDateTime timestamp
  ) {
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

  private static HttpResponseMessage unprocessableEntity(
      String detail,
      String correlationId,
      ZonedDateTime timestamp
  ) {
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
