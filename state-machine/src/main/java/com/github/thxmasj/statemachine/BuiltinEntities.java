package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestDispatching;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestRouting;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Begin;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Completed;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Dispatched;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Rejected;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.RejectedRollbackFromCompleted;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.RejectedRollbackFromDispatched;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.RolledBack;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.RollingBack;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.HttpRequestRouter.initialTransitionFor;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.RequestParser.ParsedRequest;
import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EntitySelector.ByIdFromSession;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.OutgoingRequestCreator.Context;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Validated.Invalid;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseCreator;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.github.thxmasj.statemachine.message.http.UnprocessableEntity;
import jakarta.annotation.Nullable;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

public class BuiltinEntities {

  public static SecondaryIdModel<MessageId> MessageId = new SecondaryIdModel<>() {
    @Override
    public String name() {return "MessageId";}

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("ClientId", "VARCHAR(100)", id -> ((MessageId) id).clientId()),
          new Column("Value", "VARCHAR(100)", id -> ((MessageId) id).value())
      );
    }

    @Override
    public SecondaryId<MessageId> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(this, new MessageId(resultSet.getString("ClientId"), resultSet.getString("Value")));
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };
  public static SecondaryIdModel<MessageId> RollbackMessageId = new SecondaryIdModel<>() {
    @Override
    public String name() {return "RollbackMessageId";}

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("ClientId", "VARCHAR(100)", id -> ((MessageId) id).clientId()),
          new Column("Value", "VARCHAR(100)", id -> ((MessageId) id).value())
      );
    }

    @Override
    public SecondaryId<MessageId> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(this, new MessageId(resultSet.getString("ClientId"), resultSet.getString("Value")));
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };

  public enum States implements State {Begin, Routed, Completed, Rejected, RollingBack, RolledBack, Dispatched, RejectedRollbackFromCompleted, RejectedRollbackFromDispatched}
  public enum Models implements EntityModel {
    RequestDispatching {
      private final static UUID id = UUID.fromString("9711bee2-b42b-45ba-8d5e-7f891995a9c9");
      @Override public UUID id() {return id;}
      @Override public State initialState() {return Begin;}
      @Override public List<SecondaryIdModel<?>> secondaryIds() {return List.of(MessageId, RollbackMessageId);}
    },
    RequestRouting {
      private final static UUID id = UUID.fromString("20755705-fc81-4228-a1a7-5e13d6e3c153");
      @Override public UUID id() {return id;}
      @Override public State initialState() {return Begin;}
    }
  }

  public static EventType<HttpRequestMessage, Void> RouteRequest = BasicEventType.of("Route request", UUID.fromString("e55c0077-eddd-4840-b880-2bf0ace4468a"), HttpRequestMessage.class, Void.class);
  public static EventType<Tuple3<String, EntityId, HttpRequestMessage>, HttpRequestMessage> RejectRequest = BasicEventType.of(
      "Reject request",
      UUID.fromString("46106429-3c44-45cd-be04-5eb341c8f381"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class, HttpRequestMessage.class),
      HttpRequestMessage.class
  );
  public static EventType<Tuple2<HttpRequestMessage, HttpResponseMessage>, HttpRequestMessage> RejectDuplicateRequest = BasicEventType.of(
      "Reject duplicate request",
      UUID.fromString("304f5036-4ae6-406a-9512-f217e34e542b"),
      new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, HttpResponseMessage.class),
      HttpRequestMessage.class
  );
  public static EventType<Tuple2<ParsedRequest<?>, EventReference>, Tuple2<HttpRequestMessage, EventReference>> AcceptRequest = BasicEventType.of(
      "Accept request",
      UUID.fromString("9615c3fb-4f15-47f5-b5d3-6149a6164d70"),
      new DataType<>(new TypeReference<>() {}, ParsedRequest.class, EventReference.class),
      new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, EventReference.class)
  );
  public static EventType<Tuple3<String, ParsedRequest<?>, EventReference>, HttpRequestMessage> AcceptRollbackRequest = BasicEventType.of(
      "Accept rollback request",
      UUID.fromString("abd02d03-8bdc-4bea-9135-214f31489965"),
      new DataType<>(new TypeReference<>() {}, String.class, ParsedRequest.class, EventReference.class),
      HttpRequestMessage.class
  );
  public static ResponseEventType<Tuple2<String, EventReference>> CompleteRequest = new ResponseEventType<>(
      "Complete request",
      UUID.fromString("fa608cc7-9a2b-42ec-ab83-5207ab3978a0"),
      new DataType<>(new TypeReference<>() {}, String.class, EventReference.class)
  );
  public static ResponseEventType<Tuple2<String, EventReference>> CompleteRollbackRequest = new ResponseEventType<>(
      "Complete rollback request",
      UUID.fromString("57fd7951-b922-42ff-99ed-6752f1d23024"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class)
  );
  public static EventType<HttpResponseMessage, HttpResponseMessage> CompleteDuplicatedRequest = BasicEventType.of(
      "Complete duplicated request",
      UUID.fromString("c448eb33-b5de-4201-9d58-55b9767727b6"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  public static ResponseEventType<Tuple2<String, EventReference>> CompleteInvalidRequest = new ResponseEventType<>(
      "Complete invalid request",
      UUID.fromString("6bc64696-3b9e-4897-b9d0-de3abf567685"),
      new DataType<>(new TypeReference<>() {}, String.class, EventReference.class)
  );
  public static EventType<HttpResponseMessage, HttpResponseMessage> CompleteRejectedRequest = BasicEventType.of(
      "Complete rejected request",
      UUID.fromString("887a0173-1847-4e5d-b529-3c25e1f8a5d1"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  public static EventType<String, Void> RejectAsUnroutable = BasicEventType.of("RejectAsUnroutable", UUID.fromString("9b6a57f8-8d3b-46b4-aeef-d8864f19fe13"), String.class, Void.class);
  public static EventType<HttpResponseMessage, HttpResponseMessage> Respond = BasicEventType.of(
      "Respond",
      UUID.fromString("2387a6a5-a483-428d-839f-26b5fe485247"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  public static EventType<String, HttpResponseMessage> RespondBadRequest = BasicEventType.of("RespondBadRequest", UUID.fromString("a8225270-6a22-4f1f-ae17-5b5519d46df1"), String.class, HttpResponseMessage.class);

  public static class RequestParser {

    public record ParsedRequest<T>(
        HttpRequestMessage request,
        T body,
        MessageId messageId,
        String authorizedSubject,
        EntitySelector processSelector
    ) {}
  }

  private static final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  private static final Validator jsonValidator = Validation.byDefaultProvider()
      .configure()
      .messageInterpolator(new ParameterMessageInterpolator())
      .buildValidatorFactory()
      .getValidator();

  public static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> bodyType) {
    return jsonParser(bodyType, true);
  }

  public static <T> Function<HttpRequestMessage, Validated<T>> jsonParser(Class<T> bodyType, boolean validate) {
      return request -> {
        if (bodyType == Void.class) {
          return new Valid<>(null);
        } else {
          try {
            T value = objectMapper.readValue(request.body(), bodyType);
            Set<ConstraintViolation<T>> violations = validate ? jsonValidator.validate(value) : Set.of();
            return violations.isEmpty() ?
                new Valid<>(value) :
                new Invalid<>(violations.stream()
                    .map(v -> v == null ? "n/a" : v.getPropertyPath() + ": " + v.getMessage())
                    .collect(joining(", ")));
          } catch (JsonProcessingException e) {
            return new Invalid<>("Failed to parse body with " + bodyType.getName() + ": " + e.getMessage());
          }
        }
      };
  }

  public static EventTrigger<HttpRequestMessage, HttpRequestMessage, ?> inboxTrigger = new EventTrigger<>(
      new EventSpec<>(RouteRequest, Function.identity()),
      List.of(_ -> new ById(new EntityId.UUID(UUID.randomUUID()), AlwaysCreate)),
      RequestRouting,
      false
  );


  public static Map<State, List<TransitionModel<?, ?>>> requestRoutingTransitions(
      List<HttpRequestRoute<?>> routes
  ) {
    return Map.of(
        Begin, List.of(
            initialTransitionFor(routes)
        ),
        States.Routed, List.of(
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
        .newIdentifier(MessageId, Tuple2::t2)
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
            d -> tuple("Rolled back", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isRejected() && d.t1().t1().request().message().equals(route.normalizer().apply(d.t2().rejected().log().one(AcceptRequest).t1()).message())).then(
            onEvent(RejectDuplicateRequest).to(Rejected)
                .assembleInput()
                .trigger(CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(
                d.t1().t1().request(),
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
            d -> tuple("Conflict", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .otherwise(
            onEvent(route.dispatchingEventType()).to(Dispatched)
                .assembleInput()
                .trigger(route.processEventType()).with(route.processInput()).on(route.processType()).identifiedBy(ParsedRequest::processSelector)
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
                ),
            d -> d.t1().t1()
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
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request)
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(AcceptRollbackRequest).to(RollingBack)
                .assembleInput()
                .newIdentifier(RollbackMessageId, d -> new MessageId(d.t2().messageId().clientId(), d.t2().messageId().value()))
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

  public static Map<State, List<TransitionModel<?, ?>>> requestDispatchingTransitions(
      List<HttpRequestRoute<?>> routes,
      List<CustomResponse<?>> customResponses
  ) {
    return Map.of(
        Begin, join(
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(route -> !route.isRollback())
                .map(BuiltinEntities::triggerProcessTransition)
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
            customResponses.stream().map(BuiltinEntities::customResponse).collect(toList()),
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
                    .assemble(c -> tuple(c.input(), c.log().one(AcceptRollbackRequest), c.log().one(CompleteRollbackRequest), c.log().entityId()))
                    .when(d -> {
                      boolean isEq = d.t1().request().message().equals(route.normalizer().apply(d.t2()).message());
                      if (!isEq) {
                        System.out.printf("NOT EQUAL ROLLBACK REPEAT:\nOriginal:\n%s\nRepeat:%s\n", d.t1().request().message(), route.normalizer().apply(d.t2()).message());
                      } else {
                        System.out.println("EQUAL ROLLBACK REPEAT");
                      }
                      return isEq;
                    }).then(
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

  public record CustomResponse<I>(
      ResponseEventType<I> event,
      Function<TransitionContext<I>, HttpResponseMessage> messageCreator
  ) {}

  public static class ResponseEventType<I> implements EventType<I, ResponseEventType.Data> {

    public record Data(HttpResponseMessage response, EventReference processReference) {}
    private final String name;
    private final UUID id;
    private final DataType<I> inputDataType;
    private final DataType<Data> outputDataType = new DataType<>(Data.class);

    public ResponseEventType(String name, UUID id, DataType<I> inputDataType) {
      this.name = name;
      this.id = id;
      this.inputDataType = inputDataType;
    }

    public ResponseEventType(String name, UUID id, Class<I> inputDataType) {
      this.name = name;
      this.id = id;
      this.inputDataType = new DataType<>(inputDataType);
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
    public DataType<I> inputDataType() {
      return inputDataType;
    }

    @Override
    public DataType<Data> outputDataType() {
      return outputDataType;
    }
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

  public static HttpResponseMessage createResponseMessage(
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

  public record MessageId(String clientId, String value) {}

  public record EventReference(UUID entityId, int eventNumber) {}

      @SafeVarargs
    public static List<TransitionModel<?, ?>> join(
        List<TransitionModel<?, ?>>... lists
    ) {
      var result = new ArrayList<TransitionModel<?, ?>>();
      for (var list : lists) {
        result.addAll(list);
      }
      return result;
    }

    public static String from(String line, String pattern, int captureGroup) {
      Matcher matcher = Pattern.compile(pattern).matcher(line);
      return matcher.find() ? matcher.group(captureGroup) : null;
    }

}
