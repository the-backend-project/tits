package com.github.thxmasj.statemachine.http.inbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EntityId;
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
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.EventReference;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.MessageId;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.ParsedRequest;
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

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RejectAsUnroutable;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.RespondBadRequest;
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
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

public interface TransitionModels {

  static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(List<HttpRequestRoute<?>> routes) {
    return Map.of(
        Begin, List.of(
            onEvent(RouteRequest).to(Routed)
                .assembleInput()
                .choice(routes.stream().map(TransitionModels::metadataRouteTransition).collect(toList()), d -> d)
                .otherwise(
                    onEvent(RejectAsUnroutable).to(Rejected)
                        .assembleInput()
                        .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                        .output(),
                    _ -> "No route matching request metadata"
                )
        ),
        HttpInbox.States.Routed, List.of(
            onEvent(HttpInbox.Respond).to(Completed)
                .assembleInput()
                .output(d -> d)
        ),
        Rejected, List.of(
            onEvent(HttpInbox.RespondBadRequest).to(Completed)
                .assemble(c -> c)
                .output(d -> badRequest(d.input(), d.correlationId(), d.timestamp()))
        ),
        Completed, List.of()
    );
  }

  private static <T> GuardedTransition<HttpRequestMessage, HttpRequestMessage, Void> metadataRouteTransition(HttpRequestRoute<T> metadataRoute) {
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
                metadataRoute.contentRoutes().stream().map(contentRoute -> contentRouteTransition(contentRoute)).collect(toList()),
                d -> tuple(d.t1(), d.t2().validValue())
            )
            .otherwise(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                _ -> "No route matching content"
            )
    );
  }

  private static <T, U> GuardedTransition<Tuple2<HttpRequestMessage, Validated<T>>, Tuple2<HttpRequestMessage, T>, Void> contentRouteTransition(ContentRoute<T, U> contentRoute) {
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
                            secondaryId(HttpInbox.MessageId, d.messageId(), CreateIfNotExists) :
                            new ById(new EntityId.UUID(UUID.randomUUID()), AlwaysCreate)
                    )
                    .output(),
                d -> {
                  String clientId = d.t4() != null ? d.t4().validValue() : "N/A";
                  String messageId = d.t3() != null ? d.t3().validValue() : null;
                  return new ParsedRequest<>(
                      d.t1(),
                      d.t2(),
                      messageId != null ? new MessageId(clientId, messageId) : null,
                      d.t4() != null ? d.t4().validValue() : null,
                      d.t5() != null ? d.t5().validValue() : null
                  );
                }
            )
    );
  }

  private static <T, U> TransitionModel<?, ?> triggerProcessTransition(ContentRoute<T, U> route) {
    return onEvent(route.dispatchingEventType()).to(Dispatched)
        .assemble((input, _) -> tuple(
            input,
            ofNullable(input.messageId()).orElse(new MessageId(
                    "N/A",
                    route.processEventType().id() + "/" + switch (input.processSelector()) {
                      case ById s -> s.id().value().toString();
                      case BySecondaryId<?> s -> s.value().toString();
                      case ByIdFromSession _ -> UUID.randomUUID().toString(); // TODO: Use id from session for route.processType().
                      // TODO: ByLastInIdGroup, ByNextInIdGroup not handled
                      default -> throw new IllegalStateException("Process selector type not handled: " + input.processSelector().getClass().getName());
                    }
                )
            )
        ))
        .newIdentifier(HttpInbox.MessageId, Tuple2::t2)
        .when(d -> d.t2().isRejected() && d.t2().rejected().log().oneIfExists(HttpInbox.AcceptRollbackRequest).isPresent()).then(
            onEvent(HttpInbox.RejectRequest).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(HttpInbox.CompleteRejectedRequest).with(d -> unprocessableEntity(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Rolled back", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isRejected() && d.t1().t1().request().message().equals(route.normalizer().apply(d.t2().rejected().log().one(
            HttpInbox.AcceptRequest).t1()).message())).then(
            onEvent(HttpInbox.RejectDuplicateRequest).to(Rejected)
                .assembleInput()
                .trigger(HttpInbox.CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(
                d.t1().t1().request(),
                d.t2().rejected().log().one(ResponseEventType.Data.class).response()
            )
        )
        .when(d -> d.t2().isRejected()).then(
            onEvent(HttpInbox.RejectRequest).to(Rejected)
                .assemble(c -> tuple(
                    c.input().t1(), // reason
                    c.input().t2(), // original entity id
                    c.input().t3(), // request
                    c.correlationId(),
                    c.timestamp()
                ))
                .trigger(HttpInbox.CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .otherwise(
            onEvent(route.dispatchingEventType()).to(Dispatched)
                .assembleInput()
                .trigger(route.processEventType()).with(route.processInput()).on(route.processType()).identifiedBy(
                    HttpInbox.ParsedRequest::processSelector)
                .when(d -> d.t2().isRejected()).then(
                  onEvent(HttpInbox.RejectRequest).to(Rejected)
                      .assembleInput()
                      .trigger(HttpInbox.CompleteRequest)
                      .with(d -> tuple(d.t1(), new EventReference(d.t2().value(), 0))) // TODO
                      .on(RequestDispatching)
                      .identifiedBy(entityIdFromSession())
                      .output(d -> d.t1().t3()),
                  d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().request())
                )
                .when(d -> d.t2().isUnknownId()).then(
                    onEvent(HttpInbox.RejectRequest).to(Rejected)
                        .assemble(c -> c)
                        .trigger(HttpInbox.CompleteRejectedRequest).with(d -> badRequest("No such entity", d.correlationId(), d.timestamp())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().input().t3()),
                    d -> tuple(d.t2().unknownId().exception().getMessage(), null, d.t1().request())
                )
                .when(_ -> route.processEventType() instanceof BasicEventType.Rollback).then(
                    onEvent(HttpInbox.AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .trigger(HttpInbox.CompleteRequest).with(d -> tuple("Cancelled", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .output(d -> tuple(d.t1().t1().request(), d.t1().t2())),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                )
                .when(d -> d.t2().isAccepted()).then(
                    onEvent(HttpInbox.AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .output(d -> tuple(d.t1().request(), d.t2())),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                )
                .when(d -> d.t2().isCompleted()).then(
                    onEvent(HttpInbox.AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .output(d -> tuple(d.t1().request(), d.t2())),
                    d -> tuple(d.t1(), new EventReference(d.t2().completed().entityId().value(), d.t2().completed().eventNumber()))
                )
                .when(d -> d.t2() instanceof ProcessResult.Pending<?, ?, ?> p && p.exception().entityModel().equals(RequestRouting)).then(
                    // TODO: Can immediate reply in router be handled like this?
                    onEvent(HttpInbox.AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .output(d -> tuple(d.t1().request(), d.t2())),
                    d -> tuple(d.t1(), new EventReference(d.t2().pending().exception().entityId().value(), 0 /* eventNumber not used in transition anyways */))
                )
                .otherwise(
                    // TODO: How to handle unknown process result? ("should never happen")
                    onEvent(HttpInbox.AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .output(d -> tuple(d.t1().request(), d.t2())),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                ),
            d -> d.t1().t1()
        );
  }

  private static <T, U> TransitionModel<?, ?> triggerRollbackTransition(ContentRoute<T, U> route, State rejectedState) {
    return onEvent(route.dispatchingEventType()).to(RollingBack)
        .assemble((input, log) -> tuple(input, log.entityId(), log.one(HttpInbox.AcceptRequest).t2(), log.one(ResponseEventType.Data.class).processReference()))
        .trigger(Rollback).with(d -> new Data(d.t3().eventNumber() - 1, d.t4().eventNumber(), "HTTP request")).on(route.processType()).identifiedBy(d -> entityId(d.t3().entityId()))
        .when(d -> d.t2().isRejected()).then(
            onEvent(HttpInbox.RejectRequest).to(rejectedState)
                .assembleInput()
                .trigger(HttpInbox.CompleteRollbackRequest).with(d -> tuple(d.t1(), new EventReference(d.t2().value(), 0))).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(HttpInbox.AcceptRollbackRequest).to(RollingBack)
                .assembleInput()
                .newIdentifier(HttpInbox.RollbackMessageId, d -> new MessageId(d.t2().messageId().clientId(), d.t2().messageId().value()))
                .trigger(HttpInbox.CompleteRollbackRequest).with(d -> tuple(d.t1().t1(), d.t1().t3())).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
                .map(TransitionModels::triggerProcessTransition)
                .collect(toList()),
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(ContentRoute::isRollback)
                .map(route ->
                    onEvent(route.dispatchingEventType()).to(RollingBack)
                        .assemble(c -> tuple(c.input(), c.log().entityId()))
                        .otherwise(
                            onEvent(HttpInbox.AcceptRollbackRequest).to(RollingBack)
                                .assembleInput()
                                .newIdentifier(HttpInbox.MessageId, d -> d.t2().messageId())
                                .trigger(HttpInbox.CompleteRollbackRequest)
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
                onEvent(HttpInbox.CompleteRequest).to(Completed)
                    .assemble(c -> new ResponseEventType.Data(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                onEvent(HttpInbox.CompleteInvalidRequest).to(Completed)
                    .assemble(c -> new ResponseEventType.Data(createResponseMessage(new BadRequest(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                    .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
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
            onEvent(HttpInbox.CompleteRollbackRequest).to(RolledBack)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(HttpInbox.CompleteDuplicatedRequest).to(RolledBack)
                .assemble(TransitionContext::input)
                .trigger(HttpInbox.Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        Rejected, List.of(
            onEvent(HttpInbox.CompleteRequest).to(Completed)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(HttpInbox.CompleteRejectedRequest).to(Completed)
                .assembleInput()
                .trigger(HttpInbox.Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            onEvent(HttpInbox.CompleteDuplicatedRequest).to(Completed)
                .assemble(TransitionContext::input)
                .trigger(HttpInbox.Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromCompleted, List.of(
            onEvent(HttpInbox.CompleteRollbackRequest).to(Completed)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RejectedRollbackFromDispatched, List.of(
            onEvent(HttpInbox.CompleteRollbackRequest).to(Dispatched)
                .assemble(c -> new ResponseEventType.Data(createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1()), c.input().t2()))
                .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        RolledBack,
        // For handling rollback repeats properly
        routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(ContentRoute::isRollback)
            .map(route ->
                onEvent(route.dispatchingEventType()).to(RollingBack)
                    .assemble(c -> tuple(c.input(), c.log().one(HttpInbox.AcceptRollbackRequest), c.log().one(
                        HttpInbox.CompleteRollbackRequest), c.log().entityId()))
                    .when(d -> d.t1().request().message().equals(route.normalizer().apply(d.t2()).message())).then(
                        onEvent(HttpInbox.RejectDuplicateRequest).to(RollingBack)
                            .assembleInput()
                            .trigger(HttpInbox.CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> tuple(d.t1().request(), d.t3().response())
                    )
                    .otherwise(
                        onEvent(HttpInbox.RejectRequest).to(Rejected)
                            .assemble(c -> tuple(
                                c.input().t1(), // reason
                                c.input().t2(), // original entity id
                                c.input().t3(), // request
                                c.correlationId(),
                                c.timestamp()
                            ))
                            .trigger(HttpInbox.CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
        .trigger(HttpInbox.Respond).with(ResponseEventType.Data::response).on(RequestRouting).identifiedBy(entityIdFromSession())
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
