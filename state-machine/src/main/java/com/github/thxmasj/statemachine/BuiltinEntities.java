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
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.OutgoingRequestCreator.Context;
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
  public static SecondaryIdModel<EventReference> EventReference = new SecondaryIdModel<>() {
    @Override
    public String name() {return "EventReference";}

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("Entity", "UNIQUEIDENTIFIER", id -> ((EventReference) id).entityId()),
          new Column("EventNumber", "SMALLINT", id -> ((EventReference) id).eventNumber())
      );
    }

    @Override
    public SecondaryId<EventReference> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(
            this,
            new EventReference(
                UUID.fromString(resultSet.getString("Entity")),
                resultSet.getInt("EventNumber")
            )
        );
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
      @Override public List<SecondaryIdModel<?>> secondaryIds() {return List.of(MessageId, RollbackMessageId, EventReference);}
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
  public static EventType<Tuple2<ParsedRequest<?>, EventReference>, HttpRequestMessage> AcceptRequest = BasicEventType.of(
      "Accept request",
      UUID.fromString("9615c3fb-4f15-47f5-b5d3-6149a6164d70"),
      new DataType<>(new TypeReference<>() {}, ParsedRequest.class, EventReference.class),
      HttpRequestMessage.class
  );
  public static EventType<Tuple2<String, ParsedRequest<?>>, HttpRequestMessage> AcceptRollbackRequest = BasicEventType.of(
      "Accept rollback request",
      UUID.fromString("abd02d03-8bdc-4bea-9135-214f31489965"),
      new DataType<>(new TypeReference<>() {}, String.class, ParsedRequest.class),
      HttpRequestMessage.class
  );
  public static EventType<Tuple2<String, EntityId>, HttpResponseMessage> CompleteRequest = BasicEventType.of(
      "Complete request",
      UUID.fromString("fa608cc7-9a2b-42ec-ab83-5207ab3978a0"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class),
      HttpResponseMessage.class
  );
  public static EventType<Tuple2<String, EntityId>, HttpResponseMessage> CompleteRollbackRequest = BasicEventType.of(
      "Complete rollback request",
      UUID.fromString("57fd7951-b922-42ff-99ed-6752f1d23024"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class),
      HttpResponseMessage.class
  );
  public static EventType<HttpResponseMessage, HttpResponseMessage> CompleteDuplicatedRequest = BasicEventType.of(
      "Complete duplicated request",
      UUID.fromString("c448eb33-b5de-4201-9d58-55b9767727b6"),
      HttpResponseMessage.class,
      HttpResponseMessage.class
  );
  public static EventType<Tuple2<String, EntityId>, HttpResponseMessage> CompleteInvalidRequest = BasicEventType.of(
      "Complete invalid request",
      UUID.fromString("6bc64696-3b9e-4897-b9d0-de3abf567685"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class),
      HttpResponseMessage.class
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
  public static EventType<Tuple2<String, EntityId>, HttpResponseMessage> RespondCreated = BasicEventType.of(
      "RespondCreated",
      UUID.fromString("809591b7-4b4a-4ed6-ba8d-e17814204c8b"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class),
      HttpResponseMessage.class
  );
  public static EventType<Tuple2<String, EntityId>, HttpResponseMessage> RespondOk = BasicEventType.of(
      "RespondOk",
      UUID.fromString("0c4df594-d32b-4ff5-adbd-9e6d2a180b42"),
      new DataType<>(new TypeReference<>() {}, String.class, EntityId.class),
      HttpResponseMessage.class
  );
  public static EventType<String, HttpResponseMessage> RespondUnprocessableContent = BasicEventType.of("RespondUnprocessableContent", UUID.fromString("3ee039d0-c912-4058-9380-6b70e0921470"), String.class, HttpResponseMessage.class);
  public static EventType<String, HttpResponseMessage> RespondBadRequest = BasicEventType.of("RespondBadRequest", UUID.fromString("a8225270-6a22-4f1f-ae17-5b5519d46df1"), String.class, HttpResponseMessage.class);
  public static EventType<String, HttpResponseMessage> RespondSeeOther = BasicEventType.of("RespondSeeOther", UUID.fromString("89e26f64-575f-466d-bb71-bd82ad574126"), String.class, HttpResponseMessage.class);

  public static class RequestParser {

    public record ParsedRequest<T>(
        HttpRequestMessage request,
        T body,
        MessageId messageId,
        String authorizedSubject,
        EntitySelector processSelector
    ) {}

//    public static <T> Validated<ParsedRequest<T>> parseRequest(
//        HttpRequestMessage request,
//        Function<HttpRequestMessage, Validated<T>> bodyParser,
//        BiFunction<HttpRequestMessage, T, String> messageIdParser,
//        boolean authorize
//    ) {
//      T body = null;
//      if (bodyParser != null) {
//        Validated<T> validatedBody = bodyParser.apply(request);
//        if (validatedBody.isInvalid())
//          return new Invalid<>(validatedBody.invalid());
//        body = validatedBody.valid();
//      }
//      ParsedAuthorizationClaims authorizationClaims = null;
//      if (authorize) {
//        authorizationClaims = ParsedAuthorizationClaims.claimsFromBearerToken(request);
//        if (authorizationClaims.isInvalid()) return new Invalid<>(authorizationClaims.invalid().error());
//      }
//      MessageId messageId = messageIdParser != null ? new MessageId(
//          authorize ? authorizationClaims.valid().subject() : "N/A",
//          messageIdParser.apply(request, body)
//      ) : null;
//      return new Valid<>(new ParsedRequest<>(request, body, messageId, authorize ? authorizationClaims.valid() : null));
//    }
  }

//  public record HttpRequestRouter<T, U>(
//      Predicate<HttpRequestMessage> metaDataRoutingPredicate,
//      Predicate<Tuple2<HttpRequestMessage, T>> contentRoutingPredicate,
//      boolean authorize,
//      EventType<HttpRequestMessage, Void> metaDataRoutingEventType,
//      EventType<ParsedRequest<T>, Void> dispatchingEventType,
//      EventType<U, ?> processEventType,
//      EntityModel processEntity,
//      EntitySelector<ParsedRequest<T>> processEntitySelector,
//      Function<HttpRequestMessage, Validated<T>> bodyParser,
//      BiFunction<HttpRequestMessage, T, String> messageIdParser,
//      Function<ParsedRequest<T>, U> processInput
//  ) {}

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

//  private static <T> GuardedTransition<HttpRequestMessage, ParsedRequest<T>, Void> rule(HttpRequestRouter<T, ?> r, boolean rollback) {
//    return new GuardedTransition<>(
//        r.metaDataRoutingPredicate,
//        //m -> parseRequest(m, r.bodyParser, r.messageIdParser, r.authorize),
//        onEvent(r.metaDataRoutingEventType).to(Routed)
//            .assemble(d -> tuple(d.input(), r.bodyParser().apply(d.input())))
//            .when(d -> d.t2().isInvalid()).then(/* invalid body */)
//            .when(/* BODY routing 1 */)
//            .when(/* BODY routing 2 */)
//            .trigger(r.dispatchingEventType).with(d -> d).on(RequestDispatching).identifiedBy(rollback ? secondaryId(MessageId, d -> d.messageId, CreateIfNotExists) : newEntityId())
//            .output()
//    );
//  }

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
//            onEvent(RouteRequest).to(Routed)
//                .assembleInput()
//                .when(routers.stream().map(r -> rule(r, false)).collect(toList()))
//                .when(rollbackRouters.stream().map(r -> rule(r, true)).collect(toList()))
//                .otherwise(
//                    onEvent(RejectAsUnroutable).to(Rejected)
//                        .assembleInput()
//                        .trigger(RespondBadRequest).with(d -> "No route for " + d).on(RequestRouting).identifiedBy(entityIdFromSession())
//                        .output(),
//                    HttpRequestMessage::requestLine
//                )
        ),
        States.Routed, List.of(
            onEvent(Respond).to(Completed)
                .assembleInput()
                .output(d -> d)
//            onEvent(RespondCreated).to(Completed)
//                .assemble(c -> c)
//                .output(d -> createResponseMessage(new Created(), d.input().t2(), d.correlationId(), d.timestamp(), d.input().t1())),
//            onEvent(RespondOk).to(Completed)
//                .assemble(c -> c)
//                .output(d -> createResponseMessage(new OK(), d.input().t2(), d.correlationId(), d.timestamp(), d.input().t1())),
//            onEvent(RespondUnprocessableContent).to(Completed)
//                .assemble(c -> c)
//                .output(d -> createResponseMessage(new BadRequest(), d.log().entityId(), d.correlationId(), d.timestamp(), d.input())),
//            onEvent(RespondSeeOther).to(Completed)
//                .assemble(c -> c)
//                .output(d -> createResponseMessage(new SeeOther(), d.log().entityId(), d.correlationId(), d.timestamp(), d.input()))
        ),
        Rejected, List.of(
            onEvent(RespondBadRequest).to(Completed)
                .assemble(c -> c)
                .output(d -> badRequest(d.input(), d.log().entityId(), d.correlationId(), d.timestamp()))
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
                    switch (input.processSelector()) {
                      case ById s -> s.id().value().toString();
                      case BySecondaryId<?> s -> s.value().toString();
                      // TODO: ByIdFromSession, ByLastInIdGroup, ByNextInIdGroup not handled
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
                .trigger(CompleteRejectedRequest).with(d -> unprocessableEntity(d.t1(), d.t2(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Rolled back", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .when(d -> d.t2().isRejected() && d.t1().t1().request().message().equals(route.normalizer().apply(d.t2().rejected().log().one(HttpRequestMessage.class)).message())).then(
            onEvent(RejectDuplicateRequest).to(Rejected)
                .assembleInput()
                .trigger(CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            d -> tuple(d.t1().t1().request(), d.t2().rejected().log().one(HttpResponseMessage.class))
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
                .trigger(CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t2(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple("Conflict", d.t2().rejected().log().entityId(), d.t1().t1().request())
        )
        .otherwise(
            onEvent(route.dispatchingEventType()).to(Dispatched)
                .assembleInput()
                .trigger(route.processEventType()).with(route.processInput()).on(route.processType()).identifiedBy(d -> d.processSelector())
                .when(d -> d.t2().isRejected()).then(
                  onEvent(RejectRequest).to(Rejected)
                      .assembleInput()
                      .trigger(CompleteRequest).with(d -> tuple(d.t1(), d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                      .output(d -> d.t1().t3()),
                  d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().request())
                )
                .when(d -> d.t2().isUnknownId()).then(
                    onEvent(RejectRequest).to(Rejected)
                        .assemble(c -> c)
                        .trigger(CompleteRejectedRequest).with(d -> badRequest("No such entity", d.log().entityId(), d.correlationId(), d.timestamp())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().input().t3()),
                    d -> tuple(d.t2().unknownId().exception().getMessage(), null, d.t1().request())
                )
                .when(_ -> route.processEventType() instanceof BasicEventType.Rollback).then(
                    onEvent(AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .trigger(CompleteRequest).with(d -> tuple("Cancelled", d.t3())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1().request()),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                )
                .when(d -> d.t2().isAccepted()).then(
                    onEvent(AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .newIdentifier(EventReference, Tuple3::t2)
                        .output(d -> d.t1().t1().request()),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                )
                .when(d -> d.t2().isCompleted()).then(
                    onEvent(AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .newIdentifier(EventReference, Tuple3::t2)
                        .output(d -> d.t1().t1().request()),
                    d -> tuple(d.t1(), new EventReference(d.t2().completed().entityId().value(), d.t2().completed().eventNumber()))
                ).otherwise(
                    // TODO: How to handle unknown process result? ("should never happen")
                    onEvent(AcceptRequest).to(Dispatched)
                        .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                        .newIdentifier(EventReference, Tuple3::t2)
                        .output(d -> d.t1().t1().request()),
                    d -> tuple(d.t1(), new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber()))
                ),
            d -> d.t1().t1()
        );
  }

  private static <T, U> TransitionModel<?, ?> triggerRollbackTransition(ContentRoute<T, U> route, State rejectedState) {
    return onEvent(route.dispatchingEventType()).to(RollingBack)
        .assemble((input, log) -> tuple(input, log.entityId(), log.id(EventReference)))
        .trigger(Rollback).with(d -> new Data(d.t3().eventNumber() - 1, "HttpInbox")).on(route.processType()).identifiedBy(d -> entityId(d.t3().entityId()))
        .when(d -> d.t2().isRejected()).then(
            onEvent(RejectRequest).to(rejectedState)
                .assembleInput()
                .trigger(CompleteRollbackRequest).with(d -> tuple(d.t1(), d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t3()),
            d -> tuple(d.t2().rejected().exception().getMessage(), d.t2().rejected().exception().entityId(), d.t1().t1().request)
        )
        .when(d -> d.t2().isAccepted()).then(
            onEvent(AcceptRollbackRequest).to(RollingBack)
                .assemble((input, log) -> tuple(
                    input.t1(),
                    input.t2(),
                    log.entityId()
                ))
// Can only have one secondary id of a certain type per entity (type maps to table, entity id is PK)
// RollbackMessageId? Then we can also avoid the "R" hack.
                .newIdentifier(RollbackMessageId, d -> new MessageId(d.t2().messageId().clientId(), d.t2().messageId().value()))
                .trigger(CompleteRollbackRequest).with(d -> tuple(d.t1().t1(), d.t1().t3())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t2().request()),
            d -> tuple(
                "Rolled back", // to event number " + d.t2().accepted().event().getUnmarshalledData().toNumber(),
                d.t1().t1()
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
                .map(route -> triggerProcessTransition(route))
                .collect(toList()),
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(route -> route.isRollback())
                .map(route ->
                    onEvent(route.dispatchingEventType()).to(RollingBack)
                        .assemble(c -> tuple(c.input(), c.log().entityId()))
                        .otherwise(
                            onEvent(AcceptRollbackRequest).to(RollingBack)
                                .assemble((input, log) -> tuple(input.t1(), input.t2(), log.entityId()))
                                .newIdentifier(MessageId, d -> d.t2().messageId())
                                .trigger(CompleteRequest)
                                .with(d -> tuple(d.t1().t1(), d.t1().t3()))
                                .on(RequestDispatching)
                                .identifiedBy(entityIdFromSession())
                                .output(d -> d.t1().t1().t2().request()),
                            d -> tuple(
                                "Nothing to roll back",
                                d.t1()
                            )
                        )
                ).collect(toList())
        ),
        Dispatched, join(
            customResponses.stream().map(BuiltinEntities::customResponse).collect(toList()),
            List.of(
                customResponse(completeRequest),
                customResponse(new CustomResponse<>(
                    CompleteInvalidRequest,
                    c -> createResponseMessage(new BadRequest(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1())
                ))
            ),
            routes.stream()
                .flatMap(r -> r.contentRoutes().stream())
                .filter(route -> route.isRollback())
                .map(r -> triggerRollbackTransition(r, RejectedRollbackFromDispatched))
                .collect(toList())
        ),
        Completed, routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(route -> route.isRollback())
            .map(route -> triggerRollbackTransition(route, RejectedRollbackFromCompleted))
            .collect(toList()),
        RollingBack, List.of(
            customResponse(new CustomResponse<>(CompleteRollbackRequest, c -> createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1())), RolledBack),
            customResponse(new CustomResponse<>(CompleteDuplicatedRequest, TransitionContext::input), RolledBack)
        ),
        Rejected, List.of(
            customResponse(rejectRequest),
            customResponse(completeRejectedRequest),
            customResponse(new CustomResponse<>(CompleteDuplicatedRequest, TransitionContext::input))
        ),
        RejectedRollbackFromCompleted, List.of(customResponse(rejectRequest)),
        RejectedRollbackFromDispatched, List.of(customResponse(rejectRequest, Dispatched)),
        RolledBack,
        // For handling rollback repeats properly
        routes.stream()
            .flatMap(r -> r.contentRoutes().stream())
            .filter(route -> route.isRollback())
            .map(route ->
                onEvent(route.dispatchingEventType()).to(RollingBack)
                    .assemble(c -> tuple(c.input(), c.log().one(AcceptRollbackRequest), c.log().one(CompleteRollbackRequest), c.log().entityId()))
                    .when(d -> {
                      boolean isEq = d.t1().request().message().equals(route.normalizer().apply(d.t2()).message());
                      if (!isEq) {
                        System.out.printf("NOT EQUAL ROLLBACK REPEAT:\nOriginal:\n%s\nRepeat:%s\n", d.t1().request().message(), route.normalizer().apply(d.t2()).message());
                      }
                      return isEq;
                    }).then(
                        onEvent(RejectDuplicateRequest).to(RollingBack)
                            .assembleInput()
                            .trigger(CompleteDuplicatedRequest).with(Tuple2::t2).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> tuple(d.t1().request(), d.t3())
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
                            .trigger(CompleteRejectedRequest).with(d -> badRequest(d.t1(), d.t2(), d.t4(), d.t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t3()),
                        d -> tuple("Conflict", d.t4(), d.t1().request())
                    )
            ).collect(toList())
    );
  }

  public record CustomResponse<I>(
      EventType<I, HttpResponseMessage> event,
      Function<TransitionContext<I>, HttpResponseMessage> messageCreator
  ) {}

  private static <I> TransitionModel<?, ?> customResponse(CustomResponse<I> customResponse) {
    return onEvent(customResponse.event()).to(Completed)
        .assemble(customResponse.messageCreator())
        .trigger(Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static <I> TransitionModel<?, ?> customResponse(CustomResponse<I> customResponse, State target) {
    return onEvent(customResponse.event()).to(target)
        .assemble(customResponse.messageCreator())
        .trigger(Respond).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static final CustomResponse<?> completeRequest = new CustomResponse<>(
      CompleteRequest,
      c -> createResponseMessage(new Created(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1())
  );

  private static final CustomResponse<?> completeRejectedRequest = new CustomResponse<>(
      CompleteRejectedRequest,
      TransitionContext::input
  );

  private static final CustomResponse<?> rejectRequest = new CustomResponse<>(
      CompleteRequest,
      c -> createResponseMessage(new UnprocessableEntity(), c.input().t2(), c.correlationId(), c.timestamp(), c.input().t1())
  );

  //  public abstract static class InboxExchange implements EntityModel {
//
//    @Override
//    public int hashCode() {
//      return id().hashCode();
//    }
//
//    @Override
//    public boolean equals(Object other) {
//      return other instanceof InboxExchange ie && ie.id().equals(id());
//    }
//
//    public enum State implements com.github.thxmasj.statemachine.State {
//      Requested,
//      Responded,
//      RollingBack,
//      RolledBack
//    }

//    interface HttpRequestMessageEvent extends EventType<HttpRequestMessage, HttpRequestMessage> {
//
//      @Override
//      default DataType<HttpRequestMessage> inputDataType() {
//        return new DataType<>(HttpRequestMessage.class);
//      }
//
//      @Override
//      default DataType<HttpRequestMessage> outputDataType() {
//        return new DataType<>(HttpRequestMessage.class);
//      }
//
//    }
//
//    public record RequestType(String name, UUID id) implements HttpRequestMessageEvent {}
//
//    public static RequestType Request = new RequestType(
//        "Request",
//        UUID.fromString("bb3d9b74-d5ba-49ae-992d-1934ee7be79f")
//    );
//
//    interface HttpResponseMessageEvent extends EventType<HttpResponseMessage, HttpResponseMessage> {
//
//      @Override
//      default DataType<HttpResponseMessage> inputDataType() {
//        return new DataType<>(HttpResponseMessage.class);
//      }
//
//      @Override
//      default DataType<HttpResponseMessage> outputDataType() {
//        return new DataType<>(HttpResponseMessage.class);
//      }
//
//    }
//
//    public record ResponseType(String name, UUID id) implements HttpResponseMessageEvent {}
//
//    public static ResponseType Response = new ResponseType(
//        "Response",
//        UUID.fromString("f4e745c9-0e52-405e-82b4-e5b43e3b315f")
//    );
//
//    public static EventType<String, Void> InvalidRequest = BasicEventType.of(
//        "InvalidRequest",
//        UUID.fromString("ae58df43-0a96-4749-afb8-33f29f7aa0df"),
//        String.class,
//        Void.class
//    );
//    public static EventType<EntityId, Void> AcceptedRequest = BasicEventType.of(
//        "AcceptedRequest",
//        UUID.fromString("837f24e1-e3d8-48df-b39d-8bd6e4e7f96f"),
//        EntityId.class,
//        Void.class
//    );
//    public static EventType<EntityId, Void> AcceptedRollbackRequest = BasicEventType.of(
//        "AcceptedRollbackRequest",
//        UUID.fromString("6185215d-667c-4146-974d-77ccc6431319"),
//        EntityId.class,
//        Void.class
//    );
//    public static EventType<String, Void> SeeOther = BasicEventType.of(
//        "SeeOther",
//        UUID.fromString("52bbff54-aa60-4fba-a78c-1625dcdda4e2"),
//        String.class,
//        Void.class
//    );
//    public static EventType<Tuple3<HttpRequestMessage, MessageId, String>, HttpRequestMessage> RejectedRequest =
//        BasicEventType.of(
//            "RejectedRequest",
//            UUID.fromString("63a1c14d-b0e8-459b-9eb5-fac9035c8450"),
//            new DataType<>(new TypeReference<Tuple3<HttpRequestMessage, MessageId, String>>() {}, HttpRequestMessage.class, MessageId.class, String.class),
//            HttpRequestMessage.class
//        );
//    public static EventType<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> RejectedDuplicatedRequest =
//        BasicEventType.of(
//            "RejectedDuplicatedRequest",
//            UUID.fromString("26013ed4-f986-4b3f-ba63-1e168f88daf1"),
//            new DataType<Tuple2<HttpRequestMessage, EventLog>>(
//                new TypeReference<Tuple2<HttpRequestMessage, EventLog>>() {},
//                HttpRequestMessage.class,
//                EventLog.class
//            ),
//            new DataType<>(HttpRequestMessage.class)
//        );
//    public static EventType<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> DuplicatedRequest =
//        BasicEventType.of(
//            "DuplicatedRequest",
//            UUID.fromString("85db8899-cc28-4199-8b37-03b683d4c823"),
//            new DataType<Tuple2<HttpRequestMessage, EventLog>>(
//                new TypeReference<Tuple2<HttpRequestMessage, EventLog>>() {},
//                HttpRequestMessage.class,
//                EventLog.class
//            ),
//            new DataType<>(HttpRequestMessage.class)
//        );
//    public static EventType<HttpRequestMessage, HttpRequestMessage> RollbackRequest = BasicEventType.of(
//        "RollbackRequest",
//        UUID.fromString("d5364301-87dc-4aaf-a5af-a8dc2b488aba"),
//        HttpRequestMessage.class
//    );
//    public static EventType<HttpResponseMessage, HttpResponseMessage> RollbackResponse = BasicEventType.of(
//        "RollbackResponse",
//        UUID.fromString("c02d864d-bf25-4a45-98f4-fe579a7eedbf"),
//        HttpResponseMessage.class
//    );
//
//    @Override
//    public String toString() {
//      return "InboxExchange";
//    }
//
//    @Override
//    public String name() {
//      return "InboxExchange";
//    }
//
//    @Override
//    public UUID id() {
//      return UUID.fromString("9387d8ca-4ecb-4c85-abc1-5ff432485ae8");
//    }
//
//
//    @Override
//    public com.github.thxmasj.statemachine.State initialState() {
//      return Begin;
//    }
//
//    @Override
//    public List<SecondaryIdModel<?>> secondaryIds() {
//      return List.of(MessageId, EventReference);
//    }
//
//    protected abstract Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes();
//
//    protected abstract List<TransitionModel<?, ?>> responseTransitions();
//
//    protected Predicate<HttpRequestMessage> rollbackPredicate() {
//      return _ -> false;
//    }
//
//    protected EntityModel rollbackEntity() {
//      return null;
//    }
//
//    @SafeVarargs
//    public static List<TransitionModel<?, ?>> join(
//        List<TransitionModel<?, ?>>... lists
//    ) {
//      var result = new ArrayList<TransitionModel<?, ?>>();
//      for (var list : lists) {
//        result.addAll(list);
//      }
//      return result;
//    }
//
//    public TransitionModel<Tuple3<HttpRequestMessage, MessageId, String>, HttpRequestMessage> rejectedRequest() {
//      return onEvent(RejectedRequest).to(Requested)
//          .assemble(d -> d)
//          .trigger(Response)
//          .with(d -> unprocessableEntity(
//              d.input().t3(),
//              d.log().entityId(),
//              d.correlationId(),
//              d.timestamp()
//          ))
//          .on(this)
//          .identifiedBy(entityIdFromSession())
//          .newIdentifier(MessageId, d -> d.t1().input().t2())
//          .output(d -> d.t1().t1().input().t1());
//    }
//
//    public TransitionModel<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> invalidDuplicatedRequest(String reason) {
//      return onEvent(RejectedDuplicatedRequest).to(Requested)
//          .assemble(d -> d)
//          .trigger(Response)
//          .with(d -> badRequest(reason, d.log().entityId(), d.correlationId(), d.timestamp()))
//          .on(this)
//          .identifiedBy(entityIdFromSession())
//          .output(d -> d.t1().input().t1());
//    }
//
//    public TransitionModel<Tuple2<HttpRequestMessage, EventLog>, HttpRequestMessage> duplicatedRequest() {
//      return onEvent(DuplicatedRequest).to(Requested)
//          .assembleInput()
//          // Trigger Response with data from original Response
//          .trigger(Response).with(d -> d.t2().one(Response)).on(this).identifiedBy(entityIdFromSession())
//          .output(d -> d.t1().t1());
//    }
//
//    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> initialRollbackRequest(Function<HttpRequestMessage, MessageId> messageIdCreator) {
//      return onEvent(RollbackRequest).to(RollingBack)
//          .assemble((input, log) -> tuple(input, log.entityId()))
//          .newIdentifier(MessageId, d -> messageIdCreator.apply(d.t1()))
//          .trigger(AcceptedRollbackRequest).with(d -> d.t1().t2()).on(this).identifiedBy(entityIdFromSession())
//          .output(d -> d.t1().t1().t1());
//    }
//
//    protected TransitionModel<HttpRequestMessage, HttpRequestMessage> rollbackRequestOn(EntityModel on) {
//      return onEvent(RollbackRequest).to(RollingBack)
//          .assemble((input, log) -> tuple(input, log.entityId(), log.id(EventReference)))
//          .trigger(Rollback)
//          .with(d -> new Data(d.t3().eventNumber() - 1, "Inbox"))
//          .on(on)
//          .identifiedBy(entityId(d -> d.t3().entityId()))
//          .trigger(AcceptedRollbackRequest)
//          .with(d -> d.t1().t2())
//          .on(this)
//          .identifiedBy(entityIdFromSession())
//          .output(d -> d.t1().t1().t1());
//    }
//
//    public static String from(String line, String pattern, int captureGroup) {
//      Matcher matcher = Pattern.compile(pattern).matcher(line);
//      return matcher.find() ? matcher.group(captureGroup) : null;
//    }
//
//    public Map<com.github.thxmasj.statemachine.State, List<TransitionModel<?, ?>>> transitions() {
//      return Map.of(
//          Begin, List.of(
//              onEvent(Request).to(Requested)
//                  //.whenReject(rejectedRequest())
//                  .whenDuplicate(
//                      MessageId,
//                      (input, log) -> input.message().equals(log.one(HttpRequestMessage.class).message()),
//                      duplicatedRequest()
//                  )
//                  .whenDuplicate(MessageId, (_, log) -> log.oneIfExists(RollbackResponse).isPresent(), invalidDuplicatedRequest("Rolled back"))
//                  .whenDuplicate(MessageId, (_, _) -> true, invalidDuplicatedRequest("Conflict"))
//                  .assembleInput()
//                  .when2(routes())
//                  .when(_ -> true).then(invalidRequest(), m -> "Request not mapped: " + m.requestLine())
//                  .output(d -> d),
//              onEvent(RollbackRequest).to(RollingBack)
//                  .assemble((input, log) -> tuple(input, log.entityId()))
//                  // Identifier should be created by trigger (CreateIfNotExists)
//                  //.newIdentifier(MessageId, d -> messageIdCreator.apply(d.t1()))
//                  .trigger(AcceptedRollbackRequest).with(Tuple2::t2).on(this).identifiedBy(entityIdFromSession())
//                  .output(d -> d.t1().t1())
//          ),
//          Requested, join(
//              List.of(
//                  onEvent(Response).to(Responded).assembleInput().output(d -> d),
//                  onEvent(AcceptedRequest).to(Responded)
//                      .assemble(c -> tuple(c.input(), c.timestamp(), c.correlationId()))
//                      .when(_ -> true).then(
//                          onEvent(Response).to(Responded).assembleInput().output(d -> d),
//                          d -> createResponseMessage(new Created(), d.t1(), d.t3(), d.t2(), "Created")
//                      ).output(),
//                  onResponseEvent(InvalidRequest, new BadRequest()),
//                  onResponseEvent(SeeOther, new SeeOther())
//              ),
//              responseTransitions()
//          ),
//          Responded, List.of(
//              onEvent(RollbackRequest).to(RollingBack)
//                  .assemble((input, log) -> tuple(input, log.entityId(), log.id(EventReference)))
//                  .trigger(Rollback)
//                  .with(d -> new Data(d.t3().eventNumber() - 1, "Inbox"))
//                  .on(rollbackEntity())
//                  .identifiedBy(entityId(d -> d.t3().entityId()))
//                  .trigger(AcceptedRollbackRequest)
//                  .with(d -> d.t1().t2())
//                  .on(this)
//                  .identifiedBy(entityIdFromSession())
//                  .output(d -> d.t1().t1().t1())
//
////              onEvent(Request).to(RollingBack)
////                  .assembleInput()
////                  .when(rollbackPredicate()).then(rollbackRequestOn(rollbackEntity()))
////                  .when(_ -> true).then(invalidRequest(), _ -> "Request not mapped")
////                  .output(d -> d)
//          ),
//          RollingBack, List.of(
//              onEvent(RollbackResponse).to(State.RolledBack).assembleInput().output(d -> d),
//              onEvent(AcceptedRollbackRequest).to(State.RolledBack)
//                  .assemble(c -> c)
//                  .when(_ -> true).then(
//                      onEvent(RollbackResponse).to(State.RolledBack).assembleInput().output(d -> d),
//                      d -> createResponseMessage(
//                          new Created(),
//                          d.log().entityId(),
//                          d.correlationId(),
//                          d.timestamp(),
//                          "Rolled back"
//                      )
//                  ).output()
//          ),
//          State.RolledBack, List.of()
//      );
//    }

//  }

//  protected static TransitionModel<String, Void> invalidRequest() {
//    return onResponseEvent(InvalidRequest, new BadRequest());
//  }

//  protected static TransitionModel<String, Void> onResponseEvent(
//      EventType<String, Void> eventType,
//      HttpResponseCreator responseCreator
//  ) {
//    return onEvent(eventType).to(Responded)
//        .assemble(c -> tuple(c.input(), c.log().entityId(), c.timestamp(), c.correlationId()))
//        .when(_ -> true).then(
//            onEvent(InboxExchange.Response).to(Responded).assembleInput().output(d -> d),
//            d -> createResponseMessage(responseCreator, d.t2(), d.t4(), d.t3(), d.t1())
//        ).output();
//  }
//
//  private static HttpResponseMessage created(
//      EntityId entityId,
//      String correlationId,
//      ZonedDateTime timestamp
//  ) {
//    return createResponseMessage(new Created(), entityId, correlationId, timestamp, "Created");
//  }

  private static HttpResponseMessage badRequest(
      String detail,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return createResponseMessage(new BadRequest(), entityId, correlationId, timestamp, detail);
  }

  private static HttpResponseMessage unprocessableEntity(
      String detail,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp
  ) {
    return createResponseMessage(new UnprocessableEntity(), entityId, correlationId, timestamp, detail);
  }

  public static HttpResponseMessage createResponseMessage(
      HttpResponseCreator responseCreator,
      EntityId entityId,
      String correlationId,
      ZonedDateTime timestamp,
      String data
  ) {
    return responseCreator.create(
        data, new Context() {
          @Override
          public EntityId entityId() {
            return entityId;
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
