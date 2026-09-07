package com.github.thxmasj.statemachine.http.inbox;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.AcceptRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.AcceptRollbackRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteDuplicatedRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteInvalidRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRejectedRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRollbackRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.MessageId;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RejectAsUnroutable;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RejectDuplicateRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RejectRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.Respond;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RespondBadRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RollbackMessageId;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RouteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Begin;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Completed;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Dispatched;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Rejected;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.RejectedRollbackFromCompleted;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.RejectedRollbackFromDispatched;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.RolledBack;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.RollingBack;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.States.Routed;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EntitySelector.ByIdFromSession;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.GuardedTransition;
import com.github.thxmasj.statemachine.OutgoingRequestCreator.Context;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.CustomResponse;
import com.github.thxmasj.statemachine.EventReference;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.MessageId;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.RouteId;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseCreator;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.UnprocessableEntity;
import jakarta.annotation.Nullable;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;

public interface TransitionModels {

  static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(List<HttpRequestRoute<?>> routes) {
    return Map.of(
        Begin, List.of(
            onEvent(RouteRequest).to(Routed)
                .assembleInput()
                .choice(IntStream.range(0, routes.size()).mapToObj(i -> TransitionModels.metadataRouteTransition(i, routes.get(i))).collect(toList()))
                .otherwise(
                    onEvent(RejectAsUnroutable).to(Rejected)
                        .assembleInput()
                        .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                        .output(),
                    _ -> "No route matching request metadata"
                )
        ),
        Routed, List.of(
            onEvent(Respond).to(Completed)
                .assembleInput()
                .output(d -> d)
        ),
        Rejected, List.of(
            onEvent(RespondBadRequest).to(Completed)
                .assemble(c -> c)
                .output(d -> badRequest(d.input(), d.correlationId(), d.timestamp()))
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
        onEvent(RouteRequest).to(Routed)
            .assemble(c -> tuple(
                    c.input(),
                    metadataRoute.contentParser() != null ? metadataRoute.contentParser().apply(c.input()) : null
                )
            )
            .when(d -> d.t2() != null && d.t2().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
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
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
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
        contentRoute.predicateName(),
        contentRoute.dispatchingEventTypeId(),
        new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, Object.class),
        Void.class
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
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Unable to parse message id: " + d.t3().invalidReason()
            )
            .when(d -> d.t4() != null && d.t4().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> d.t4().invalidReason()
            )
            .when(d -> d.t5() != null && d.t5().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Unable to parse process selector: " + d.t5().invalidReason()
            )
            .otherwise(
                onEvent(contentRoute.dispatchingEventType()).to(Routed)
                    .assembleInput()
                    .trigger(contentRoute.dispatchingEventType()).with(d -> d).on(RequestDispatching)
                    .identifiedBy(d ->
                        contentRoute.isRollback() ?
                            secondaryId(MessageId, d.messageId(), CreateIfNotExists) :
                            new ById(new EntityId.UUID(UUID.randomUUID()), AlwaysCreate)
                    )
                    .output(),
                d -> {
                  String clientId = d.t4() != null ? d.t4().validValue() : "N/A";
                  String messageIdValue = d.t3() != null ? d.t3().validValue() : null;
                  EntitySelector processSelector = d.t5() != null ? d.t5().validValue() : null;
                  MessageId messageId = null;
                  if (messageIdValue != null) {
                    messageId = new MessageId(clientId, messageIdValue);
                  } else if (processSelector != null) {
                    messageId = new MessageId(
                        clientId,
                        contentRoute.processEventType().id() + "/" + switch (processSelector) {
                          case ById s -> s.id().value().toString();
                          case BySecondaryId<?> s -> s.value().toString();
                          case ByIdFromSession _ -> UUID.randomUUID().toString(); // TODO: Use id from session for route.processType().
                          // TODO: ByLastInIdGroup, ByNextInIdGroup not handled
                          default -> throw new IllegalStateException("Process selector type not handled: " + processSelector.getClass().getName());
                        }
                    );
                  }
                  return new RoutedRequest<>(
                      routeId,
                      d.t1(),
                      d.t2(),
                      messageId,
                      d.t4() != null ? d.t4().validValue() : null,
                      processSelector
                  );
                }
            ),
        dataAdapter
    );
  }

  private static <T, U> TransitionModel<?, ?> dispatchChoiceTransition(ContentRoute<T, U> route) {
    return onEvent(route.dispatchingEventType()).to(Dispatched)
        .assembleInput()
        .newIdentifier(MessageId, RoutedRequest::messageId)
        .when(d -> d.t2().isRejected() && d.t2().rejected().log().oneIfExists(AcceptRollbackRequest).isPresent()).then(
            onEvent(RejectRequest).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(CompleteRejectedRequest).with(d -> unprocessableEntity(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Rolled back", d.t2().rejected().log().entityId(), d.t1().request())
        )
        .when(d -> d.t2().isRejected() && d.t1().request().message().equals(route.normalizer().apply(d.t2().rejected().log().one(AcceptRequest).t1()).message())).then(
            onEvent(RejectDuplicateRequest).to(Rejected)
                .assembleInput()
                .trigger(CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(
                d.t1().request(),
                d.t2().rejected().log().one(ResponseEventType.Data.class).response()
            )
        )
        .when(d -> d.t2().isRejected()).then(
            onEvent(RejectRequest).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t2().rejected().log().entityId(), d.t1().request())
        )
        .otherwise(dispatchTransition(route), Tuple2::t1);
  }

  private static <T, U> TransitionModel<RoutedRequest<T>, Void> dispatchTransition(ContentRoute<T, U> route) {
    return onEvent(route.dispatchingEventType()).to(Dispatched)
        .assembleInput()
        .trigger(route.processEventType()).with(route.processInput()).on(route.processType()).identifiedBy(
            RoutedRequest::processSelector)
        .when(d -> d.t2().isRejected()).then(
            onEvent(RejectRequest).to(Rejected)
                .assembleInput()
                .trigger(CompleteRequest)
                .with(d -> tuple(d.t1(), new EventReference(d.t2().value(), 0))) // TODO
                .on(RequestDispatching)
                .identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().request())
        )
        .when(d -> d.t2().isUnknownId()).then(
            onEvent(RejectRequest).to(Rejected)
                .assemble(c -> c)
                .trigger(CompleteRejectedRequest).with(d -> badRequest("No such entity", d.correlationId(), d.timestamp())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().input().t3()),
            d -> tuple(d.t2().unknownId().exception().getMessage(), null, d.t1().request())
        )
        .when(_ -> route.processEventType() instanceof BasicEventType.Rollback).then(
            onEvent(AcceptRequest).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .trigger(CompleteRequest).with(d -> tuple("Cancelled", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> tuple(d.t1().t1().request(), d.t1().t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(AcceptRequest).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        )
        .when(d -> d.t2().isCompleted()).then(
            onEvent(AcceptRequest).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().completed().entityId().value(), d.t2().completed().eventNumber()))
        )
        .when(d -> d.t2() instanceof ProcessResult.Pending<?, ?, ?> p && p.exception().entityModel().equals(RequestRouting)).then(
            // TODO: Can immediate reply in router be handled like this?
            onEvent(AcceptRequest).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().pending().exception().entityId().value(), 0 /* eventNumber not used in transition anyways */))
        )
        .otherwise(
            // TODO: How to handle unknown process result? ("should never happen")
            onEvent(AcceptRequest).to(Dispatched)
                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                .output(d -> tuple(d.t1().request(), d.t2())),
            d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
        );
  }

  private static <T, U> TransitionModel<?, ?> triggerRollbackTransition(ContentRoute<T, U> route, State rejectedState) {
    return onEvent(route.dispatchingEventType()).to(RollingBack)
        .assemble((input, log) -> tuple(input, log.entityId(), log.one(AcceptRequest).t2(), log.one(ResponseEventType.Data.class).processReference()))
        .trigger(Rollback).with(d -> new Data(d.t3().eventNumber() - 1, d.t4().eventNumber(), "HTTP request")).on(route.processType()).identifiedBy(d -> entityId(d.t3().entityId()))
        .when(d -> d.t2().isRejected()).then(
            onEvent(RejectRequest).to(rejectedState)
                .assembleInput()
                .trigger(CompleteRollbackRequest).with(d -> tuple(d.t1(), new EventReference(d.t2().value(), 0))).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(AcceptRollbackRequest).to(RollingBack)
                .assembleInput()
                .newIdentifier(RollbackMessageId, d -> d.t2().messageId())
                .trigger(CompleteRollbackRequest).with(d -> tuple(d.t1().t1(), d.t1().t3())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t2().request()),
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
                            onEvent(AcceptRollbackRequest).to(RollingBack)
                                .assembleInput()
                                .newIdentifier(MessageId, d -> d.t2().messageId())
                                .trigger(CompleteRollbackRequest)
                                .with(d -> tuple(d.t1().t1(), d.t1().t3()))
                                .on(RequestDispatching)
                                .identifiedBy(entityIdFromSession())
                                .output(d -> d.t1().t1().t2().request()),
                            d -> tuple("Nothing to roll back", d.t1(), null) // TODO: EventReference is null
                        )
                ).collect(toList())
        ),
        Dispatched, join(
            customResponses.stream().map(TransitionModels::customResponse).collect(toList()),
            List.of(
                onEvent(CompleteRequest).to(Completed)
                    .assemble(c -> new ResponseEventType.Data(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                onEvent(CompleteInvalidRequest).to(Completed)
                    .assemble(c -> new ResponseEventType.Data(createResponseMessage(new BadRequest(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
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
            onEvent(CompleteRollbackRequest).to(RolledBack)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(CompleteDuplicatedRequest).to(RolledBack)
                .assemble(TransitionContext::input)
                .trigger(Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        Rejected, List.of(
            onEvent(CompleteRequest).to(Completed)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(CompleteRejectedRequest).to(Completed)
                .assembleInput()
                .trigger(Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(CompleteDuplicatedRequest).to(Completed)
                .assemble(TransitionContext::input)
                .trigger(Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromCompleted, List.of(
            onEvent(CompleteRollbackRequest).to(Completed)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromDispatched, List.of(
            onEvent(CompleteRollbackRequest).to(Dispatched)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RolledBack,
        // For handling rollback repeats properly
        routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(ContentRoute::isRollback)
            .map(route ->
                onEvent(route.dispatchingEventType()).to(RollingBack)
                    .assemble(c -> tuple(c.input(), c.log().one(AcceptRollbackRequest), c.log().one(
                        CompleteRollbackRequest), c.log().entityId()))
                    .when(d -> d.t1().request().message().equals(route.normalizer().apply(d.t2()).message())).then(
                        onEvent(RejectDuplicateRequest).to(RollingBack)
                            .assembleInput()
                            .trigger(CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> tuple(d.t1().request(), d.t3().response())
                    )
                    .otherwise(
                        onEvent(RejectRequest).to(Rejected)
                            .assemble(c -> tuple(
                                c.input().t1(), // reason
                                c.input().t2(), // original entity id
                                c.input().t3(), // request
                                c.correlationId(),
                                c.timestamp()
                            ))
                            .trigger(CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
        .assemble(c -> new ResponseEventType.Data(customResponse.messageCreator().apply(c), c.eventReference()))
        .trigger(Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static HttpResponseMessage badRequest(
      String detail,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return createResponseMessage(new BadRequest(), null, correlationId, timestamp, detail);
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
        data, new Context() {
          @Override
          public EntityId entityId() {
            return eventReference != null ? new EntityId.UUID(eventReference.entityId()) : null;
          }

          @Override
          public String correlationId() {
            return correlationId;
          }

          @Override
          public ZonedDateTime timestamp() {
            return timestamp;
          }
        }
    );
  }
}
