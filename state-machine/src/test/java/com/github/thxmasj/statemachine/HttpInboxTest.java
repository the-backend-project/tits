package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEntities.CompleteRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestDispatching;
import static com.github.thxmasj.statemachine.BuiltinEntities.inboxTrigger;
import static com.github.thxmasj.statemachine.BuiltinEntities.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EventTriggerTest.States.Processing;
import static com.github.thxmasj.statemachine.EventTriggerTest.States.Unreachable;
import static com.github.thxmasj.statemachine.HttpInboxTest.Queues.DeviceListener;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.TransitionStreamTest.Entities.Lamp;
import static com.github.thxmasj.statemachine.TransitionStreamTest.States.Off;
import static com.github.thxmasj.statemachine.TransitionStreamTest.States.On;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute.parseEntityId;
import static com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute.parseMessageId;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.DELETE;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.RequestParser.ParsedRequest;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class HttpInboxTest {

  private static final long PROCESSING_TIMEOUT = 2000;
  private static final long LONG_EXTERNAL_PROCESSING = PROCESSING_TIMEOUT + 1000;

  enum States implements State {
    On,
    Off,
    Processing {
      @Override
      public Timeout timeout() {
        return rollbackAfter(Duration.ofMillis(PROCESSING_TIMEOUT));
      }
    },
    Unreachable
  }

  private static final Random random = new Random();

  enum Entities implements EntityModel {
    Lamp {
      @Override
      public UUID id() {
        return UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f");
      }

      @Override
      public State initialState() {
        return Off;
      }

    }
  }

  private static Map<State, List<TransitionModel<?, ?>>> lampTransitions() {
    return Map.of(
        On, List.of(
            onEvent(InternalProcessing).to(Off).assembleInput().output(d -> d),
            onEvent(SwitchOff).to(Off).output(),
            onEvent(Cancel).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(CompleteRequest).with(d -> tuple("Cancelled", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(ZeroProcessing).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(CompleteRequest).with(d -> tuple("Complete", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1())
        ),
        Off, List.of(
            onEvent(InternalProcessing).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(CompleteRequest).with(d -> tuple("Light is on! " + random.nextLong(), d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ComplexInternalProcessing).to(Processing)
                .assemble(d -> d)
                .trigger(ComplexInternalProcessingDone).on(Lamp).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(new ProcessRequest(0))
                .with(_ -> null)
                .to(DeviceListener)
                .guaranteed()
                .responseValidator((_, _, _, _) -> Mono.just(new Result(
                    Status.Ok,
                    "Ok",
                    new InputEvent<>(ExternalProcessingDone, null)
                )))
                .output(),
            onEvent(LongExternalProcessing).to(Processing)
                .assembleInput()
                .trigger(new ProcessRequest(LONG_EXTERNAL_PROCESSING))
                .with(_ -> null)
                .to(DeviceListener)
                .guaranteed()
                .responseValidator((_, _, _, _) -> Mono.just(new Result(
                    Status.Ok,
                    "Ok",
                    new InputEvent<>(ExternalProcessingDone, null)
                )))
                .reversible(
                    assemble((log, rollbackType) -> "")
                        // NB: Response in request/reply session not possible with reversals triggered by the resolver
                        //.trigger(ComplexInternalProcessResponse).with(_ -> "Failed to switch on light! :(((").on(inboxExchange).identifiedBy(entityIdFromSession())
                        .trigger(new ProcessRequest(0))
                        .with(_ -> null)
                        .to(DeviceListener)
                        .guaranteed()
                        .responseValidator((_, _, _, _) -> Mono.just(new Result(
                            Status.Ok,
                            "Ok",
                            new InputEvent<>(ExternalProcessingDone, null)
                        )))
                        .complete()
                )
                .output(),
            onEvent(SwitchOn).to(On).output()
        ),
        Processing, List.of(
            onEvent(Rollback).toSelf().assembleInput().output(d -> d),
            onEvent(ComplexInternalProcessingDone).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(CompleteRequest).with(d -> tuple("Phew! Light is switched on! " + random.nextLong(), d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingDone).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(CompleteRequest).with(d -> tuple("Light is externally switched on! " + random.nextLong(), d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output()
        ),
        Unreachable, List.of(
            onEvent(EventThatIsAlwaysRejected).toSelf().output()
        )
    );
  }

  record InternalProcess(String a, int b) {}

  static EventType<Tuple2<HttpRequestMessage, InternalProcess>, HttpRequestMessage> ValidInternalProcessRequest = BasicEventType.of(
      "ValidInternalProcessRequest",
      UUID.fromString("46b0211e-f583-49b3-a6e7-8d13742e0260"),
      new DataType<>(
          new TypeReference<Tuple2<HttpRequestMessage, InternalProcess>>() {},
          HttpRequestMessage.class,
          InternalProcess.class
      ),
      new DataType<>(HttpRequestMessage.class)
  );
  static EventType<Tuple2<HttpRequestMessage, String>, HttpRequestMessage> InvalidInternalProcessRequest = BasicEventType.of(
      "InvalidInternalProcessRequest",
      UUID.fromString("6f00b6ba-b0e5-46a6-ad85-64eec4891b69"),
      new DataType<>(
          new TypeReference<Tuple2<HttpRequestMessage, String>>() {},
          HttpRequestMessage.class,
          String.class
      ),
      new DataType<>(HttpRequestMessage.class)
  );

  public record Zero(String value) {}

  static EventType<Zero, Zero>
      ZeroProcessing = BasicEventType.of(
      "ZeroProcessing",
      UUID.fromString("aa70041a-a68f-4bcd-831f-5029eb329a05"),
      Zero.class
  );
  static EventType<Void, Void>
      EventThatIsAlwaysRejected = BasicEventType.of(
      "EventThatIsAlwaysRejected",
      UUID.fromString("bd98a725-ac60-4db4-b7db-ce08a295ffca")
  ),
      InternalProcessing = BasicEventType.of(
          "InternalProcessing",
          UUID.fromString("5909336e-cc75-4c58-902f-fb68f08b0caa")
      ),
      ComplexInternalProcessing = BasicEventType.of(
          "ComplexInternalProcessing",
          UUID.fromString("16c1792d-9fb7-4f91-b55b-b59e2b00bad8")
      ),
      ComplexInternalProcessingDone = BasicEventType.of(
          "ComplexInternalProcessingDone",
          UUID.fromString("88c87c85-7778-4171-bc75-702f9e60b00e")
      ),
      ExternalProcessingDone = BasicEventType.of(
          "ExternalProcessingDone",
          UUID.fromString("464fca06-f872-4e38-a167-5550f5247310")
      );
  static EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data>
      Cancel = new BasicEventType.Cancel("Cancel", UUID.fromString("d94c29c8-f113-4dee-921f-1a8e58f916f4"));
  static EventType<String, String>
      ExternalProcessing = BasicEventType.of(
      "ExternalProcessing",
      UUID.fromString("12a97e70-58cc-46a8-aed9-f867cfc7375f"),
      String.class
  ),
      LongExternalProcessing = BasicEventType.of(
          "LongExternalProcessing",
          UUID.fromString("6ebcbbec-5bbc-4c05-96fd-41ad0bbc097a"),
          String.class
      );
  static EventType<Void, Void>
      SwitchOn = BasicEventType.of("SwitchOn", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      SwitchOff = BasicEventType.of("SwitchOff", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"));

  static <K, V> Entry<K, V> entry(K key, V value) {
    return new SimpleImmutableEntry<>(key, value);
  }

//  private static <T> Function<ParsedRequest<T>, Validated<EntitySelector<ParsedRequest<T>>>> parseEntityId(
//      String requestLinePattern,
//      int captureGroup
//  ) {
//    return d -> {
//      String s = from(d.request().requestLine(), requestLinePattern, captureGroup);
//      if (s == null) return new Invalid<>("Unable to extract entity id from request line");
//      try {
//        return new Valid<>(entityId(UUID.fromString(s)));
//      } catch (IllegalArgumentException e) {
//        return new Invalid<>("Entity id is not a valid UUID");
//      }
//    };
//  }

  static List<HttpRequestRoute<?>> routes = List.of(
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/zero/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("7e2c5175-a65d-4795-a285-b0d75e704f5a"), ZeroProcessing, Lamp, (ParsedRequest<String> _) -> new Zero("Hey!"), parseEntityId("PUT .*/zero/(.*)", 1)))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/internal/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/internal/(.*)", 1), null, UUID.fromString("3afa1f14-d4e3-49c4-b6ad-05a733f0b22d"), InternalProcessing, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/handledunknown/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("b411fd45-6c87-43aa-a511-df930d654ec7"), InternalProcessing, Lamp, (ParsedRequest<String> _) -> null, (_, _) -> new Valid<>(entityId(UUID.randomUUID())))) // Unknown id
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/handledreject/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("00f910e2-8c22-4403-835d-15e112ac3080"), EventThatIsAlwaysRejected, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/complexinternal/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/complexinternal/(.*)", 1), null, UUID.fromString("84164954-ad85-4b44-9de6-079bf4805df7"), ComplexInternalProcessing, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/external/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/external/(.*)", 1), null, UUID.fromString("415cfd95-6feb-4106-8d47-fcf41ddcb3d1"), ExternalProcessing, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/long-external/.*"),
          m -> valid(m.body()),
          List.of(anyContent(null, null, UUID.fromString("721fedb2-e715-4c4d-a8fb-e2cdb16f86e5"), LongExternalProcessing, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("PUT .*/rejected/.*"),
          m -> valid(m.body()),
          List.of(anyContent(parseMessageId("PUT .*/rejected/(.*)", 1), null, UUID.fromString("e58f3910-0b01-4f8b-bbf3-c7fde4980197"), EventThatIsAlwaysRejected, Lamp, (ParsedRequest<String> _) -> null, newEntity()))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("DELETE .*/internal/.*"),
          _ -> valid(null),
          List.of(anyContent(
              null,
              null,
              UUID.fromString("f5ef4720-c6bc-4686-be8b-85eeb0fdc372"),
              Rollback,
              Lamp,
              _ -> new Data(0, Integer.MAX_VALUE, "Cancel"),
              parseEntityId("DELETE .*/internal/(.*)", 1)
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"),
          _ -> valid(null),
          List.of(anyContent(
              parseMessageId("DELETE .*/lamps/messages/(.*)", 1),
              null,
              UUID.fromString("56829b3a-78ce-40e4-9006-a6c4d3b6dc23"),
              Rollback,
              Lamp,
              _ -> null,
              null
          ))
      )
  );

//
//  static List<HttpRequestRouter<?, ?>> rollbackRouters = List.of(
//      new HttpRequestRouter<>(
//          m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"),
//          false,
//          BasicEventType.of("RollbackRequest", UUID.fromString("9c8914f1-60f7-4829-8eaa-c0a60c65d377"), new DataType<>(null), Void.class),
//          Rollback,
//          Lamp,
//          null,
//          null,
//          (request, _) -> from(request.requestLine(), "DELETE .*/lamps/messages/(.*)", 1),
//          _ -> null
//      )
//  );


//  static List<HttpRequestRouter<?, ?>> routers = List.of(
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/zero/.*"),
//        false,
//        BasicEventType.of("ZeroProcessRequest", UUID.fromString("c5eb5fb3-8742-49e6-b8a2-351afc44304c"), new DataType<>(new TypeReference<>() {}, null), Void.class),
//        ZeroProcessing,
//        Lamp,
//        entityId(d -> UUID.fromString(requireNonNull(from(d.request().requestLine(), "PUT .*/zero/(.*)", 1)))),
//        null,
//        (request, _) -> ZeroProcessing.id() + "/" + requireNonNull(from(request.requestLine(), "PUT .*/zero/(.*)", 1)),
//        _ -> new Zero("Hey!")
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/internal/.*"),
//        false,
//        BasicEventType.of("InternalProcessRequest", UUID.fromString("74b6a36a-135f-4cef-bb78-4e6279bd337b"), new DataType<>(null), Void.class),
//        InternalProcessing,
//        Lamp,
//        newEntityId(),
//        null,
//        (request, _) -> from(request.requestLine(), "PUT .*/internal/(.*)", 1),
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/handledunknown/.*"),
//        false,
//        BasicEventType.of("HandledUnknownId", UUID.fromString("cae56547-1999-49d4-ab34-b846942f6526"), new DataType<>(null), Void.class),
//        InternalProcessing,
//        Lamp,
//        entityId(UUID.randomUUID()), // Unknown id
//        null,
//        null,
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/handledreject/.*"),
//        false,
//        BasicEventType.of("HandledReject", UUID.fromString("bd065522-30b4-4495-8f22-a0576d798cc7"), new DataType<>(null), Void.class),
//        EventThatIsAlwaysRejected,
//        Lamp,
//        newEntityId(),
//        null,
//        null,
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/complexinternal/.*"),
//        false,
//        BasicEventType.of("ComplexInternalProcessRequest", UUID.fromString("baafef75-8d74-4984-967e-cbd3215ec1de"), new DataType<>(null), Void.class),
//        ComplexInternalProcessing,
//        Lamp,
//        newEntityId(),
//        null,
//        (request, _) -> from(request.requestLine(), "PUT .*/complexinternal/(.*)", 1),
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/external/.*"),
//        false,
//        BasicEventType.of("ExternalProcessRequest", UUID.fromString("ec71b8fd-d1bd-4ebb-b993-0a62f69d21e5"), new DataType<>(null), Void.class),
//        ExternalProcessing,
//        Lamp,
//        newEntityId(),
//        null,
//        (request, _) -> from(request.requestLine(), "PUT .*/external/(.*)", 1),
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/long-external/.*"),
//        false,
//        BasicEventType.of("LongExternalProcessRequest", UUID.fromString("0b4397d3-c3cc-4950-8513-18e9522b18c5"), new DataType<>(null), Void.class),
//        LongExternalProcessing,
//        Lamp,
//        newEntityId(),
//        null,
//        null,
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("PUT .*/rejected/.*"),
//        false,
//        BasicEventType.of("RequestWhichIsRejected", UUID.fromString("fb0c25f5-e361-44be-b8fe-0632749849ff"), new DataType<>(null), Void.class),
//        EventThatIsAlwaysRejected,
//        Lamp,
//        newEntityId(),
//        null,
//        (request, _) -> from(request.requestLine(), "PUT .*/rejected/(.*)", 1),
//        _ -> null
//    ),
//    new HttpRequestRouter<>(
//        m -> m.requestLine().matches("DELETE .*/internal/.*"),
//        false,
//        BasicEventType.of("CancelRequest", UUID.fromString("86de7919-4e2f-406b-8d72-568bae6cbf32"), new DataType<>(null), Void.class),
//        Rollback,
//        Lamp,
//        entityId(d -> UUID.fromString(requireNonNull(from(d.request().requestLine(), "DELETE .*/internal/(.*)", 1)))),
//        null,
//        null,
//        _ -> new Data(0, "Cancel")
//    )
//  );






//  static InboxExchange inboxExchange = new InboxExchange() {
//
//    @Override
//    protected Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes() {
//      return Map.ofEntries(
//          entry(
//              m -> m.requestLine().matches("PUT .*/zero/.*"),
//              then(onEvent(ZeroProcessRequest).to(Requested)
//                  .assemble((input, _) -> tuple(
//                      input,
//                      UUID.fromString(requireNonNull(from(input.requestLine(), "PUT .*/zero/(.*)", 1)))
//                  ))
//                  .newIdentifier(MessageId, d -> new MessageId("x", ZeroProcessRequest.id() + "/" + d.t2().toString()))
//                  .trigger(ZeroProcessing).with(_ -> new Zero("Hey!")).on(Lamp)
//                  .identifiedBy(entityId(d -> d.t1().t2()))
//                  .output(d -> d.t1().t1().t1())
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/handledunknown/.*"),
//              then(onEvent(HandledUnknownId).to(Requested)
//                  .assembleInput()
//                  .trigger(InternalProcessing).on(Lamp).identifiedBy(entityId(UUID.randomUUID())) // Unknown id
//                  .when(d -> d.t2().isUnknownId()).then(
//                      onEvent(KnownProcessRequest).to(Requested)
//                          .assemble(d -> d.log().entityId())
//                          //.trigger(InternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                          //.trigger(AcceptedRequest).with(Tuple2::t1).on(inboxExchange).identifiedBy(entityIdFromSession())
//                          .trigger(AcceptedRequest).with(d -> d).on(inboxExchange).identifiedBy(entityIdFromSession())
//                          .output(),
//                      Tuple2::t1
//                  )
//                  .output()
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/handledreject/.*"),
//              then(onEvent(HandledReject).to(Requested)
//                  .assembleInput()
//                  .trigger(EventThatIsAlwaysRejected).on(Lamp).identifiedBy(newEntityId())
//                  .when(d -> d.t2().isRejected()).then(
//                      onEvent(InternalProcessRequest).to(Requested)
//                          .assemble(d -> d.log().entityId())
//                          //.trigger(InternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                          //.trigger(AcceptedRequest).with(Tuple2::t1).on(inboxExchange).identifiedBy(entityIdFromSession())
//                          .trigger(AcceptedRequest).with(d -> d).on(inboxExchange).identifiedBy(entityIdFromSession())
//                          .output(),
//                      Tuple2::t1
//                  )
//                  .output()
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/internal/.*"),
//              then(onEvent(InternalProcessRequest).to(Requested)
//                  .assemble((input, log) -> tuple(
//                      json(input.body(), InternalProcess.class),
//                      input,
//                      log.entityId()
//                  ))
//                  .when(d -> d.t1().isValid()).then(
//                      onEvent(ValidInternalProcessRequest).to(Requested)
//                          .assembleInput()
//                          .newIdentifier(
//                              MessageId,
//                              d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/internal/(.*)", 1))
//                          )
//                          .trigger(InternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                          .newIdentifier(
//                              EventReference,
//                              d -> new EventReference(
//                                  d.t2().accepted().event().entityId(),
//                                  d.t2().accepted().event().eventNumber()
//                              )
//                          )
//                          .output(d -> d.t1().t1().t1().t1()),
//                      d -> tuple(d.t2(), d.t1().value())
//                  )
//                  .when(d -> d.t1().isInvalid()).then(
//                      onEvent(InvalidInternalProcessRequest).to(Requested)
//                          .assembleInput()
//                          .trigger(InvalidRequest)
//                          .with(Tuple2::t2)
//                          .on(this)
//                          .identifiedBy(entityIdFromSession())
//                          .output(d -> d.t1().t1()),
//                      d -> tuple(d.t2(), "Invalid request")
//                  )
//                  .output(Tuple3::t2)
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/complexinternal/.*"),
//              then(onEvent(ComplexInternalProcessRequest).to(Requested)
//                  .assemble((input, log) -> tuple(input, log.entityId()))
//                  .newIdentifier(
//                      MessageId,
//                      d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/complexinternal/(.*)", 1))
//                  )
//                  .trigger(ComplexInternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                  .newIdentifier(
//                      EventReference,
//                      d -> new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber())
//                  )
//                  .output(d -> d.t1().t1().t1().t1())
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/external/.*"),
//              then(onEvent(ExternalProcessRequest).to(Requested)
//                  .assemble((input, log) -> tuple(input, log.entityId()))
//                  .newIdentifier(
//                      MessageId,
//                      d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/external/(.*)", 1))
//                  )
//                  .trigger(ExternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                  .newIdentifier(
//                      EventReference,
//                      d -> new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber())
//                  )
//                  .output(d -> d.t1().t1().t1().t1())
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/long-external/.*"),
//              then(onEvent(LongExternalProcessRequest).to(Requested)
//                  .assemble((input, log) -> tuple(input, log.entityId()))
//                  .newIdentifier(
//                      MessageId,
//                      d -> new MessageId("x", from(d.t1().requestLine(), "PUT .*/long-external/(.*)", 1))
//                  )
//                  .trigger(LongExternalProcessing).on(Lamp).identifiedBy(newEntityId())
//                  .newIdentifier(
//                      EventReference,
//                      d -> new EventReference(d.t2().accepted().event().entityId(), d.t2().accepted().event().eventNumber())
//                  )
//                  .output(d -> d.t1().t1().t1().t1())
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/rejected/.*"),
//              then(onEvent(RequestWhichIsRejected).to(Requested)
//                      .assemble((input, log) -> tuple(
//                          input,
//                          log.entityId(),
//                          new MessageId("x", from(input.requestLine(), "PUT .*/rejected/(.*)", 1))
//                      ))
//                      .trigger(EventThatIsAlwaysRejected)
//                      .on(Lamp)
//                      .identifiedBy(newEntityId())
//                      .when(d -> d.t2().isRejected())
//                      .then(
//                          rejectedRequest(),
//                          d -> tuple(d.t1().t1(), d.t1().t3(), d.t2().rejected().exception().getMessage())
//                      )
//                      .when(_ -> true)
//                      .then(
//                          onEvent(RequestWhichIsRejected).to(Requested)
//                              .assemble((input, log) -> tuple(
//                                  input,
//                                  log.entityId(),
//                                  new MessageId("x", from(input.requestLine(), "PUT .*/rejected/(.*)", 1))
//                              ))
////                  .newIdentifier(
////                      EventReference,
////                      d -> new EventReference(d.t2().accepted().entityId(), d.t2().accepted().event().eventNumber())
////                  )
//                              .output(),
//                          d -> d.t1().t1()
//                      )
//                      .output(d -> d.t1().t1())
//              )
//          ),
//          entry(
//              m -> m.requestLine().matches("PUT .*/oneway/.*"),
//              then(onEvent(ExternalProcessRequest).to(Requested)
//                  .assemble((input, log) -> tuple(input, log.entityId()))
//                  .output(Tuple2::t1)
//              )
//          ),
//          // RollbackRequest arrives before ToggleRequest. Create message id to block ToggleRequest if it comes
//          entry(
//              m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"),
//              then(
//                  onEvent(RollbackRequest).to(Requested)
//                      .assembleInput()
//                      .trigger(RollbackRequest).with(d -> d).on(this)
//                      .identifiedBy(secondaryId(
//                          MessageId,
//                          d -> new MessageId("x", from(d.requestLine(), "DELETE .*/lamps/messages/(.*)", 1)),
//                          CreateIfNotExists
//                      ))
//                      .output()
//              )
//          ),
////          then(initialRollbackRequest(d -> new MessageId(
////                  "x",
////                  from(d.requestLine(), "DELETE .*/lamps/messages/(.*)", 1)
////          )))),
//          entry(
//              m -> m.requestLine().matches("DELETE .*/internal/.*"),
//              then(
//                  onEvent(CancelRequest).to(Requested)
//                      .assemble((input, _) -> tuple(
//                          input,
//                          UUID.fromString(requireNonNull(from(
//                              input.requestLine(),
//                              "DELETE .*/internal/(.*)",
//                              1
//                          )))
//                      ))
//                      .newIdentifier(MessageId, d -> new MessageId("x", CancelRequest.id() + "/" + d.t2().toString()))
//                      .trigger(Cancel).with(_ -> new Data(0, "Cancel")).on(Lamp)
//                      .identifiedBy(entityId(d -> d.t1().t2()))
//                      .output(d -> d.t1().t1().t1())
//              )
//          )
//      );
//    }

//    @Override
//    protected List<TransitionModel<?, ?>> responseTransitions() {
//      return List.of(
//          onEvent(InternalProcessResponse).to(Responded)
//              .assemble(c -> tuple(c.input(), c.timestamp(), c.correlationId()))
//              .when(_ -> true).then(
//                  onEvent(Response).to(Responded).assembleInput().output(d -> d),
//                  d -> createResponseMessage(new Created(), d.t1().t1(), d.t3(), d.t2(), d.t1().t2())
//              )
//              .output(),
//          //onResponseEvent(InternalProcessResponse, new Created()),
//          onResponseEvent(ComplexInternalProcessResponse, new Created()),
//          onResponseEvent(ExternalProcessResponse, new Created())
//      );
//    }
//
//    @Override
//    protected Predicate<HttpRequestMessage> rollbackPredicate() {
//      return m -> m.requestLine().matches("DELETE .*/lamps/messages/.*");
//    }
//
//    @Override
//    protected EntityModel rollbackEntity() {
//      return Lamp;
//    }
//
//  };

  enum Queues implements OutboxQueue {
    DeviceListener {
      @Override
      public UUID id() {
        return UUID.fromString("ca9a8d7a-8342-42e1-ab58-62c61f8d4719");
      }
    };
  }

  static class ProcessRequest implements OutgoingRequestCreator<Void> {

    private final long processingTime;

    ProcessRequest(long processingTime) {this.processingTime = processingTime;}

    @Override
    public HttpRequestMessage create(Void data, Context context) {
      return new HttpRequestMessage(
          POST,
          URI.create("http://localhost:" + server.getAddress().getPort() + "/process/" + processingTime)
      );
    }

    @Override
    public UUID id() {
      return UUID.fromString("e0eadb64-224a-480c-b1a2-aab29927fb7e");
    }

    @Override
    public boolean equals(Object obj) {
      return obj instanceof ProcessRequest other && id().equals(other.id());
    }

    @Override
    public int hashCode() {
      return id().hashCode();
    }
  }

  private static StateMachine stateMachine;
  private static HttpServer server;

  @BeforeAll
  public static void setUp() throws IOException {
    server = Init.httpServer();
    Init.addDelayContext(server, "/long-process", Duration.ofSeconds(10));
    Init.addDelayContext(server, "/process", uri -> Duration.ofMillis(Long.parseLong(uri.getPath().substring("/process/".length()))));
    stateMachine = Init.stateMachine(
        Lamp,
        lampTransitions(),
        routes,
        queue -> switch (queue) {
          case DeviceListener -> new NettyHttpClient(new NettyHttpClientBuilder().build());
          default -> throw new IllegalStateException("Unexpected value: " + queue);
        }
    );
  }

  @Test
  public void whenInternalProcessRequestThenInternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenZeroingInternalProcessRequestThenAcceptedResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());

    Event<?> zeroResponseEvent = onRequest(PUT, URI.create("/zero/" + entityId), null);
    HttpResponseMessage zeroResponse = HttpMessageParser.parseResponse(zeroResponseEvent.data());
    assertEquals(201, zeroResponse.statusCode());
    assertEquals("Complete", ProblemDetail.parse(zeroResponse.body()).detail());
  }

  @Test
  public void whenCancelInternalProcessRequestThen() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsString(new InternalProcess("Hello", 1))
    );
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());
    Event<?> cancelResponseEvent = onRequest(DELETE, URI.create("/internal/" + entityId), null);
    HttpResponseMessage cancelResponse = HttpMessageParser.parseResponse(cancelResponseEvent.data());
    assertEquals(201, cancelResponse.statusCode());
  }

  @Test
  public void whenComplexInternalProcessRequestThenComplexInternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/complexinternal/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Phew! Light is switched on!"));
  }

  @Test
  public void whenRequestIsRejectedDownstreamThenUnprocessableEntityResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(422, response.statusCode());
    ProblemDetail pd = ProblemDetail.parse(response.body());
    assertEquals("EventThatIsAlwaysRejected on Lamp not allowed for Off", pd.detail());
    assertEquals(422, pd.status());
  }

  @Test
  public void whenExternalProcessRequestThenExternalProcessResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/external/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  public void whenUnknownIdThenBadRequest() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/handledunknown/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(400, response.statusCode());
    assertEquals("No such entity", ProblemDetail.parse(response.body()).detail());
  }

//  @Test
//  public void whenRejectHandledThenOkResponse() throws JsonProcessingException {
//    String messageId = UUID.randomUUID().toString();
//    Event<?> responseEvent = onRequest(PUT, URI.create("/handledreject/" + messageId), "Hello!");
//    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
//    assertEquals(201, response.statusCode());
//    assertEquals("Created", ProblemDetail.parse(response.body()).detail());
//  }

  @Test
  @Disabled("Sync response (in request/reply session) not possible with reversals triggered by the resolver")
  public void whenLongExternalProcessRequestThenRollback() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/long-external/" + messageId), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  @Disabled("TODO")
  public void whenOneWayRequestThenTimeout() throws JsonProcessingException {
    Event<?> responseEvent = onRequest(PUT, URI.create("/oneway/xxx"), "Hello!");
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenDuplicateRequestThenRespondWithSameResponse() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    InternalProcess body = new InternalProcess("Static", 0);
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response1.statusCode());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response2.statusCode());
    assertEquals(response1.body(), response2.body());
  }

  @Test
  public void whenNonDuplicateRequestWithSameMessageIdThenRespondWithBadRequest() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    HttpResponseMessage response1 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("A", 1)
    ).data());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponseMessage response2 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("B", 2)
    ).data());
    assertEquals(400, response2.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(response2.body()).detail());
  }

  @Test
  public void whenRollbackAfterRequestCompletedThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 100));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollbackResponse.body()).detail());
  }

  @Test
  public void whenRollbackBeforeRequestThenRespondOk() throws JsonProcessingException {
    String messageId = UUID.randomUUID().toString();
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponseMessage rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Nothing to roll back", ProblemDetail.parse(rollbackResponse.body()).detail());

    // Verify that a request coming in afterward is rejected (422)
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 123));
    HttpResponseMessage response = HttpMessageParser.parseResponse(responseEvent.data());
    var detail = ProblemDetail.parse(response.body()).detail();
    assertEquals(422, response.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  public void formatModel() throws IOException {
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(routes, List.of()), false).formatToImage(
        "docs/images"));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProblemDetail(String type, String title, int status, String detail, String entityId) {

    static ProblemDetail parse(String body) throws JsonProcessingException {
      var reader = new ObjectMapper().readerFor(ProblemDetail.class);
      return reader.readValue(body);
    }

  }

  private Event<?> onInternalRequest(String messageId, InternalProcess body) {
    return onRequest(PUT, URI.create("/internal/" + messageId), asJson(body));
  }

  private <T> String asJson(T value) {
    try {
      return new ObjectMapper().writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private Event<?> onRequest(Method method, URI uri, String body) {
    return onRequest(inboxTrigger, new HttpRequestMessage(method, uri, Map.of(), body));
  }

  private Event<?> onRollbackRequest(String messageId) {
    return onRequest(DELETE, URI.create("/lamps/messages/" + messageId), null);
//    var responseEvent = onRequest(
//        new EventTrigger<>(
//            new EventSpec<>(Request, Function.identity()),
//            List.of(newEntityId()),
////            List.of(secondaryId(MessageId, _ -> new MessageId("x", messageId), CreateIfNotExists)),
//            inboxExchange,
//            false
//        ),
//        new HttpRequestMessage(DELETE, URI.create("/lamps/messages/" + messageId), Map.of())
//    );
//    assertEquals(RollbackResponse, responseEvent.type());
//    return responseEvent;
  }

  private Event<?> onRequest(EventTrigger<HttpRequestMessage, ?, ?> eventTrigger, HttpRequestMessage requestMessage) {
    System.out.println(getClass().getSimpleName() + ": onRequest(...) with " + requestMessage.requestLine());
    return stateMachine.onEvent(UUID.randomUUID().toString(), eventTrigger, requestMessage)
        .doOnNext(e -> System.out.println("Test got response: " + e.type().name()))
        .switchIfEmpty(Mono.defer(() -> Mono.error(new RuntimeException("Test error"))))
        // First event is the triggered event.
        .skip(1)
        // Second event is the response event.
        .next()
        .block(Duration.ofSeconds(5));
  }
}
