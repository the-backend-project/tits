package com.github.thxmasj.backend.http.inbox;

import static com.github.thxmasj.backend.core.BuiltinEventTypes.EventsRolledBack;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.HttpRequest.Method.DELETE;
import static com.github.thxmasj.backend.http.HttpRequest.Method.POST;
import static com.github.thxmasj.backend.http.HttpRequest.Method.PUT;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.FailedRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.InvalidRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.parseEntityId;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.parseMessageId;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.when;
import static com.github.thxmasj.backend.http.inbox.HttpInboxTest.States.Off;
import static com.github.thxmasj.backend.http.inbox.HttpInboxTest.States.On;
import static com.github.thxmasj.backend.http.inbox.HttpInboxTest.States.Processing;
import static com.github.thxmasj.backend.http.inbox.HttpInboxTest.States.Unreachable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelaySpecification;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated.Valid;
import com.github.thxmasj.backend.core.internal.EventTypes;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.diagram.PlantUMLFormatter;
import com.github.thxmasj.backend.http.HttpMessageParser;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.Masking;
import com.github.thxmasj.backend.http.NettyHttpClient;
import com.github.thxmasj.backend.http.NettyHttpClientBuilder;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest.Rollback;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import tools.jackson.databind.ObjectMapper;

public class HttpInboxTest {

  enum States implements State {
    On,
    Off,
    Processing,
    Unreachable
  }

  private static final Random random = new Random();

  static final EntityModel Lamp = EntityModel.of("Lamp", UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f"), HttpInboxTest::lampTransitions);

  private static HttpOutboxRequest<Void> Process0Outbox;
  private static HttpOutboxRequest<Void> Process3Outbox;

  private static Map<State, List<TransitionModel<?, ?>>> lampTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(InternalProcessingRequested).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                //.trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(RequestCompleted).with(d -> tuple("Light is on! " + random.nextLong(), d.t1())).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ComplexInternalProcessingRequested).to(Processing)
                .assemble(d -> d)
                .trigger(ComplexInternalProcessingCompleted).on(Lamp).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingRequested).to(Processing)
                .assembleInput()
                .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                .output(),
            onEvent(LongExternalProcessingRequested).to(Processing)
                .assembleInput()
                .trigger(Process3Outbox.requestDispatched()).with(_ -> requestMessage("/process/3000")).on(Process3Outbox).identifiedBy(newEntityId())
                .reversible(
                    assemble((log, rollbackType) -> "")
                        // NB: Response in request/reply session not possible with reversals triggered by the resolver
                        //.trigger(ComplexInternalProcessResponse).with(_ -> "Failed to switch on light! :(((").on(inboxExchange).identifiedBy(entityIdFromSession())
                        .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                )
                .output(),
            onEvent(SubsystemUnavailableProcessingRequested).to(Unreachable)
                .assemble(TransitionContext::eventReference)
                .trigger(FailedRequestCompleted).with(d -> tuple("Subsystem unavailable", d)).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(LampSwitchedOn).to(On).output()
        ),
        On, List.of(
            onEvent(InternalProcessingRequested).to(Off).assembleInput().output(d -> d),
            onEvent(LampSwitchedOff).to(Off).output(),
            onEvent(ProcessingCancelled).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(RequestCompleted).with(d -> tuple("Cancelled", d.t2())).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(ZeroProcessingRequested).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(RequestCompleted).with(d -> tuple("Complete", d.t2())).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1())
        ),
        Off, List.of(
            onEvent(InternalProcessingRequested).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                //.trigger(new ProcessRequest(0)).with(_ -> null).to(DeviceListener).guaranteed()
                .trigger(RequestCompleted).with(d -> tuple("Light is on! " + random.nextLong(), d.t1())).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ComplexInternalProcessingRequested).to(Processing)
                .assemble(d -> d)
                .trigger(ComplexInternalProcessingCompleted).on(Lamp).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingRequested).to(Processing)
                .assembleInput()
                .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                .output(),
            onEvent(LongExternalProcessingRequested).to(Processing)
                .assembleInput()
                .trigger(Process3Outbox.requestDispatched()).with(_ -> requestMessage("/process/3000")).on(Process3Outbox).identifiedBy(newEntityId())
                .reversible(
                    assemble((log, rollbackType) -> "")
                        // NB: Response in request/reply session not possible with reversals triggered by the resolver
                        //.trigger(ComplexInternalProcessResponse).with(_ -> "Failed to switch on light! :(((").on(inboxExchange).identifiedBy(entityIdFromSession())
                        .trigger(Process0Outbox.requestDispatched()).with(_ -> requestMessage("/process/0")).on(Process0Outbox).identifiedBy(newEntityId())
                )
                .output(),
            onEvent(LampSwitchedOn).to(On).output()
        ),
        Processing, List.of(
            onEvent(EventsRolledBack).toSelf().assembleInput().output(d -> d),
            onEvent(ComplexInternalProcessingCompleted).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("Phew! Light is switched on! " + random.nextLong(), d)).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(ExternalProcessingCompleted).to(On)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("Light is externally switched on! " + random.nextLong(), d)).on(Request).identifiedBy(entityIdFromSession())
                .output()
        ),
        Unreachable, List.of(
            onEvent(AlwaysRejectedEventSubmitted).toSelf().output()
        )
    );
  }

  record InternalProcess(String a, int b) {}

  record Reversal(String messageId) {}

  public record Zero(String value) {}

  static EventType<Zero, Zero>
      ZeroProcessingRequested = BasicEventType.of(
      "Zero processing requested",
      UUID.fromString("aa70041a-a68f-4bcd-831f-5029eb329a05"),
      DataType.json(Zero.class)
  );
  static EventType<Void, Void>
      AlwaysRejectedEventSubmitted = BasicEventType.of(
      "Always rejected event submitted",
      UUID.fromString("bd98a725-ac60-4db4-b7db-ce08a295ffca")
  ),
      InternalProcessingRequested = BasicEventType.of(
          "Internal processing requested",
          UUID.fromString("5909336e-cc75-4c58-902f-fb68f08b0caa")
      ),
      ComplexInternalProcessingRequested = BasicEventType.of(
          "Complex internal processing requested",
          UUID.fromString("16c1792d-9fb7-4f91-b55b-b59e2b00bad8")
      ),
      ComplexInternalProcessingCompleted = BasicEventType.of(
          "Complex internal processing completed",
          UUID.fromString("88c87c85-7778-4171-bc75-702f9e60b00e")
      ),
      SubsystemUnavailableProcessingRequested = BasicEventType.of(
          "Subsystem unavailable processing requested",
          UUID.fromString("af7756d1-0566-42d8-907b-70443bc7d879")
      );
  static EventType<Void, Void> ExternalProcessingCompleted = BasicEventType.of(
      "External processing completed",
      UUID.fromString("464fca06-f872-4e38-a167-5550f5247310"),
      DataType.none()
  );
  static EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data>
      ProcessingCancelled = new BasicEventType.Cancel("Processing cancelled", UUID.fromString("d94c29c8-f113-4dee-921f-1a8e58f916f4"));
  static EventType<String, String>
      ExternalProcessingRequested = BasicEventType.of(
      "External processing requested",
      UUID.fromString("12a97e70-58cc-46a8-aed9-f867cfc7375f"),
      DataType.string()
  ),
      LongExternalProcessingRequested = BasicEventType.of(
          "Long external processing requested",
          UUID.fromString("6ebcbbec-5bbc-4c05-96fd-41ad0bbc097a"),
          DataType.string()
      );
  static EventType<Void, Void>
      LampSwitchedOn = BasicEventType.of("Lamp switched on", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      LampSwitchedOff = BasicEventType.of("Lamp switched off", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"));

  static AccessTokenVerifier verifier = verifier();

  private static HttpInboxRequest.Builder<byte[]> withBody(String requestLinePattern) {
    return HttpInboxRequest.matching(m -> m.requestLine().matches(requestLinePattern)).contentParser(m -> valid(m.body()));
  }

  /// Can be rolled back by its message id, by rollbacks which may differ in their correlation id when retried
  static HttpInboxRequest<byte[]> InternalRequest = withBody("PUT .*/internal/.*")
      .deduplicateOn(parseMessageId("PUT .*/internal/(.*)", 1))
      .process(InternalProcessingRequested, Lamp, newEntity())
      .rollback(Rollback
          .matching(m -> m.requestLine().matches("DELETE .*/lamps/messages/.*"))
          .noContent()
          .deduplicateOn(parseMessageId("DELETE .*/lamps/messages/(.*)", 1))
          .normalizer(HttpInboxTest::withoutCorrelationId))
      .build("InternalRequest", UUID.fromString("3afa1f14-d4e3-49c4-b6ad-05a733f0b22d"));

  /// One endpoint, whose requests trigger different events by their content, with different inputs and selectors
  static HttpInboxRequest<InternalProcess> ContentRequest = HttpInboxRequest
      .matching(m -> m.requestLine().matches("PUT .*/content/.*"))
      .contentParser(HttpInbox.jsonParser(InternalProcess.class, false))
      .deduplicateOn(parseMessageId("PUT .*/content/(.*)", 1))
      .process(when("a == internal", p -> p.a().equals("internal")), InternalProcessingRequested, Lamp, newEntity())
      .process(when("a == unavailable", p -> p.a().equals("unavailable")), SubsystemUnavailableProcessingRequested, Lamp, newEntity())
      .process(when("a == zero", p -> p.a().equals("zero")), ZeroProcessingRequested, Lamp, r -> new Zero(r.body().a()), parseEntityId("PUT .*/content/(.*)", 1))
      .build("ContentRequest", UUID.fromString("a3c94e1d-5f27-4b80-9d6e-1b8f2c7a4e53"));

  /// Authorized by its body, which a rollback doesn't have, so the rollback has an authorizer of its own
  static HttpInboxRequest<InternalProcess> OwnedRequest = HttpInboxRequest
      .matching(m -> m.requestLine().matches("PUT .*/owned/.*"))
      .contentParser(HttpInbox.jsonParser(InternalProcess.class, false))
      .deduplicateOn(parseMessageId("PUT .*/owned/(.*)", 1))
      .authorizer((_, body) -> Authorization.authorized(body.a()))
      .process(InternalProcessingRequested, Lamp, newEntity())
      .rollback(Rollback
          .matching(m -> m.requestLine().matches("DELETE .*/owned/messages/.*"))
          .noContent()
          .deduplicateOn(parseMessageId("DELETE .*/owned/messages/(.*)", 1))
          .authorizer((m, _) -> Authorization.authorizedElse(m.headerValue("X-Owner"), "Owner missing")))
      .build("OwnedRequest", UUID.fromString("e2b7d14c-6a93-4f08-8c5e-3d9a1f6b2c47"));

  /// Rolled back by a rollback with the message id of the request in its content
  static HttpInboxRequest<byte[]> ReversibleRequest = withBody("PUT .*/reversible/.*")
      .deduplicateOn(parseMessageId("PUT .*/reversible/(.*)", 1))
      .process(InternalProcessingRequested, Lamp, newEntity())
      .rollback(Rollback
          .matching(m -> m.requestLine().matches("POST .*/reversals"))
          .contentParser(HttpInbox.jsonParser(Reversal.class, false))
          .deduplicateOn((_, reversal) -> reversal.messageId() != null ? valid(reversal.messageId()) : invalid("messageId missing")))
      .build("ReversibleRequest", UUID.fromString("9d4e2f7a-1c58-4b36-8e0d-5a7c3f1b9e62"));

  /// Its normalizer expects a header, which a retried request may lack
  static HttpInboxRequest<byte[]> NormalizedRequest = withBody("PUT .*/normalized/.*")
      .deduplicateOn(parseMessageId("PUT .*/normalized/(.*)", 1))
      .normalizer(m -> {
        if (m.headerValue("X-Version") == null)
          throw new IllegalStateException("X-Version missing");
        return m;
      })
      .process(InternalProcessingRequested, Lamp, newEntity())
      .build("NormalizedRequest", UUID.fromString("5c2e8b17-4d9a-4f36-b0e1-7a3d6c9f2e58"));

  /// Masks a query parameter besides the credentials every inbox request masks
  static HttpInboxRequest<byte[]> MaskedRequest = withBody("PUT .*/masked/.*")
      .deduplicateOn(parseMessageId("PUT .*/masked/([^?]*).*", 1))
      .masking(Masking.queryParameters("code"))
      .process(InternalProcessingRequested, Lamp, newEntity())
      .build("MaskedRequest", UUID.fromString("c1a7e3f9-5b2d-4c80-9e46-a8d2f0b4c613"));

  /// Neither the request nor its routing is stored, while the lamp it switches on is
  static HttpInboxRequest<byte[]> NotStoredRequest = withBody("PUT .*/not-stored/.*")
      .notStored()
      .process(InternalProcessingRequested, Lamp, newEntity())
      .build("NotStoredRequest", UUID.fromString("7f3b9d1e-2c6a-4e85-b0d7-e4a8c2f6b519"));

  /// Masks the body of its response, which the routing of the other requests doesn't
  static HttpInboxRequest<byte[]> BodyMaskedRequest = withBody("PUT .*/body-masked/.*")
      .masking(Masking.responseBody())
      .process(InternalProcessingRequested, Lamp, newEntity())
      .build("BodyMaskedRequest", UUID.fromString("3e9a5c1f-7b2d-4e64-a8f0-c6d2b4e8a135"));

  static List<HttpInboxRequest<?>> requests = List.of(
      ContentRequest,
      MaskedRequest,
      BodyMaskedRequest,
      NotStoredRequest,
      NormalizedRequest,
      OwnedRequest,
      ReversibleRequest,
      withBody("PUT .*/protected/.*")
          .authorizer(HttpInbox.bearerToken(verifier, "payments"))
          .process(InternalProcessingRequested, Lamp, newEntity())
          .build("ProtectedRequest", UUID.fromString("0b6f3c1e-8a4d-4f27-9c5e-2d7a1b3e6f84")),
      withBody("PUT .*/zero/.*")
          .process(ZeroProcessingRequested, Lamp, _ -> new Zero("Hey!"), parseEntityId("PUT .*/zero/(.*)", 1))
          .build("ZeroRequest", UUID.fromString("7e2c5175-a65d-4795-a285-b0d75e704f5a")),
      InternalRequest,
      withBody("PUT .*/other-internal/.*")
          .process(InternalProcessingRequested, Lamp, newEntity())
          .build("OtherInternalRequest", UUID.fromString("6d1f4a92-3b7e-4c05-8e29-a4f6b1d7c3e8")),
      withBody("PUT .*/handledunknown/.*")
          .process(InternalProcessingRequested, Lamp, (_, _) -> valid(entityId(UUID.randomUUID()))) // Unknown id
          .build("HandledUnknownRequest", UUID.fromString("b411fd45-6c87-43aa-a511-df930d654ec7")),
      withBody("PUT .*/handledreject/.*")
          .process(AlwaysRejectedEventSubmitted, Lamp, newEntity())
          .build("HandledRejectRequest", UUID.fromString("00f910e2-8c22-4403-835d-15e112ac3080")),
      withBody("PUT .*/unavailable/.*")
          .deduplicateOn(parseMessageId("PUT .*/unavailable/(.*)", 1))
          .process(SubsystemUnavailableProcessingRequested, Lamp, newEntity())
          .build("UnavailableRequest", UUID.fromString("29d91ea2-3011-47d5-8e68-51e593a5fe3d")),
      withBody("PUT .*/complexinternal/.*")
          .deduplicateOn(parseMessageId("PUT .*/complexinternal/(.*)", 1))
          .process(ComplexInternalProcessingRequested, Lamp, newEntity())
          .build("ComplexInternalRequest", UUID.fromString("84164954-ad85-4b44-9de6-079bf4805df7")),
      withBody("PUT .*/external/.*")
          .deduplicateOn(parseMessageId("PUT .*/external/(.*)", 1))
          .process(ExternalProcessingRequested, Lamp, _ -> null, newEntity())
          .build("ExternalRequest", UUID.fromString("415cfd95-6feb-4106-8d47-fcf41ddcb3d1")),
      withBody("PUT .*/long-external/.*")
          .process(LongExternalProcessingRequested, Lamp, _ -> null, newEntity())
          .build("LongExternalRequest", UUID.fromString("721fedb2-e715-4c4d-a8fb-e2cdb16f86e5")),
      withBody("PUT .*/rejected/.*")
          .deduplicateOn(parseMessageId("PUT .*/rejected/(.*)", 1))
          .process(AlwaysRejectedEventSubmitted, Lamp, newEntity())
          .build("RejectedRequest", UUID.fromString("e58f3910-0b01-4f8b-bbf3-c7fde4980197")),
      HttpInboxRequest.matching(m -> m.requestLine().matches("DELETE .*/internal/.*"))
          .noContent()
          .process(EventsRolledBack, Lamp, _ -> new Data(0, "Cancel"), parseEntityId("DELETE .*/internal/(.*)", 1))
          .build("CancelRequest", UUID.fromString("f5ef4720-c6bc-4686-be8b-85eeb0fdc372"))
  );

  /// A state machine of the inbox, which also runs the lamps the requests trigger events on, and the outbox requests
  /// the lamps send, with their rollback models
  private static StateMachine createStateMachine(List<HttpInboxRequest<?>> requests) {
    inbox = HttpInbox.of(requests);
    return Init.stateMachine(schema, inbox.entityModels());
  }

  private static HttpRequest<Void> requestMessage(String path) {
    return HttpRequest.create(
        POST,
        URI.create("http://localhost:" + server.getAddress().getPort() + path)
    );
  }


  private static final String schema = UUID.randomUUID().toString();
  private static HttpInbox inbox;
  private static StateMachine stateMachine;
  private static HttpServer server;

  @BeforeAll
  public static void setUp() throws IOException {
    server = Init.httpServer();
    Init.addDelayContext(server, "/long-process", Duration.ofSeconds(10));
    Init.addDelayContext(server, "/process", uri -> Duration.ofMillis(Long.parseLong(uri.getPath().substring("/process/".length()))));
    Process0Outbox = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .inflightTimeout(Duration.ofSeconds(10))
        .processModel(Lamp)
        .onPeerUnavailable(ExternalProcessingCompleted)
        .onMissingResponse(ExternalProcessingCompleted)
        .onInvalidResponseRejection(ExternalProcessingCompleted)
        .onInvalidResponseUnknown(ExternalProcessingCompleted)
        .contentParser(message -> message.body() == null ? valid(null) : invalid("Expected empty body"))
        .onSuccess(ExternalProcessingCompleted)
        .onFailure(ExternalProcessingCompleted)
        .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
        .isRejected(r -> r.statusCode() >= 400 && r.statusCode() <= 499)
        .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
        .rollbackOnAcceptedModel(HttpOutboxRequest.atLeastOnce()
            .requestPayloadType(DataType.none())
            .responsePayloadType(DataType.none())
            .repeatMessageCreator((_, o) -> o)
            .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
            .inflightTimeout(Duration.ofSeconds(10))
            .contentParser(_ -> valid(null))
            .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
            .isFailureTransient(r -> r.statusCode() >= 500 && r.statusCode() <= 599)
            .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
            .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
            .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
            .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
            .build("Process0RollbackOutbox", UUID.fromString("513f7e3e-c03e-4016-b5f3-ddb981cb1ffe"))
        )
        .build("Process0Outbox", UUID.fromString("2c68ffa2-0935-46fd-a032-2647ca51b801"));
    Process3Outbox = HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.none())
        .responsePayloadType(DataType.none())
        .repeatMessageCreator((_, requestMessage) -> requestMessage)
        .forwarder(new NettyHttpClient(new NettyHttpClientBuilder().build()))
        .inflightTimeout(Duration.ofSeconds(10))
        .processModel(Lamp)
        .contentParser(_ -> valid(null))
        .onSuccess(ExternalProcessingCompleted)
        .isAccepted(r -> r.statusCode() >= 200 && r.statusCode() <= 299)
        .isFailureTransient(r -> r.statusCode() >= 500 && r.statusCode() <= 599)
        .isRejectedByInvalidResponse(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
        .isFailureByInvalidResponseTransient(r -> r.t1().statusCode() >= 400 && r.t1().statusCode() <= 499)
        .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(Duration.ofHours(5)) < 0)
        .backoffAlgorithm(c -> new DelaySpecification(Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
        .build("Process3Outbox", UUID.fromString("2e86f07c-50ed-4922-9839-54dca94be4b6"));
    stateMachine = createStateMachine(requests);
  }

  @Test
  public void whenInternalProcessRequestThenInternalProcessResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
    );
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenRequestAcceptedThenRequestIsIndexedByEntityOfProcess() throws IOException {
    onRequest(
        PUT,
        URI.create("/internal/" + UUID.randomUUID()),
        new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
    );
    // The index events of the requests accepted in this test class, by the entity id of the request
    Map<UUID, UUID> processIds = Init.client().sql(
            "SELECT EntityId, Data FROM \"" + schema + "\".\"IndexEvent\" WHERE Type=:type"
        )
        .name("Test.ProcessReferenced")
        .bind("type", HttpInbox.ProcessReferenced.id())
        .map(row -> Map.entry(row.get("EntityId", UUID.class), DataType.uuid().unmarshal(row.get("Data", byte[].class))))
        .all()
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .block();
    assertNotNull(processIds);
    assertFalse(processIds.isEmpty());
    var requestModelIds = requests.stream().map(EntityModel::id).toList();
    processIds.forEach((requestId, processId) -> {
      assertTrue(requestModelIds.contains(entityModelId(requestId)), "The index entity is that of an inbox request");
      assertEquals(Lamp.id(), entityModelId(processId), "The index key is the entity the request was processed on");
    });
  }

  /// The data of the stored event of the type on the entity, or null if there is none
  private static byte[] storedData(UUID entityId, EventType<?, ?> type) {
    return Init.client().sql("SELECT Data FROM \"" + schema + "\".\"Event\" WHERE EntityId=:entityId AND Type=:type")
        .name("Test.StoredData")
        .bind("entityId", entityId)
        .bind("type", type.id())
        .map(row -> row.get("Data", byte[].class))
        .one()
        .block();
  }

  private HttpRequest<byte[]> maskedRequest(String messageId, String code, String token) {
    return new HttpRequest<>(
        PUT,
        URI.create("/masked/" + messageId + "?code=" + code + "&state=s1"),
        Map.of("Authorization", "Bearer " + token, "Cookie", "session=" + token, "X-Other", "kept"),
        asJson(new InternalProcess("Hello", 1))
    );
  }

  @Test
  public void whenRequestIsStoredThenCredentialsAndMaskedParametersAreMasked() throws IOException {
    Event<?> response = onRequest(inbox.trigger(), maskedRequest(UUID.randomUUID().toString(), "c0de", "t0ken"));
    assertEquals(201, HttpResponse.raw(response.getUnmarshalledData()).statusCode());

    HttpRequest<byte[]> stored = HttpMessageParser.parseRequest(storedData(response.entityId(), HttpInbox.RequestRouted));
    assertEquals("code=***&state=s1", stored.uri().getRawQuery());
    assertEquals("***", stored.headerValue("Authorization"));
    assertEquals("***", stored.headerValue("Cookie"));
    assertEquals("kept", stored.headerValue("X-Other"));
    // The routing masks the response as the request routed masks it, which is not its body
    HttpResponse<byte[]> storedResponse = HttpMessageParser.parseResponse(storedData(response.entityId(), HttpInbox.ResponseCreated));
    assertTrue(new String(storedResponse.body()).contains("Light is on!"));
  }

  @Test
  public void whenRequestMasksTheBodyOfItsResponseThenItsRoutingDoesToo() {
    Event<?> response = onRequest(PUT, URI.create("/body-masked/" + UUID.randomUUID()), asJson(new InternalProcess("Hello", 1)));
    assertTrue(new String(HttpResponse.raw(response.getUnmarshalledData()).body()).contains("Light is on!"));
    HttpResponse<byte[]> stored = HttpMessageParser.parseResponse(storedData(response.entityId(), HttpInbox.ResponseCreated));
    assertEquals("***", new String(stored.body()));
  }

  @Test
  public void requestMaskingTheBodyOfItsResponseCantBeDeduplicated() {
    assertThatThrownBy(() -> withBody("PUT .*/x/.*")
        .deduplicateOn(parseMessageId("PUT .*/x/(.*)", 1))
        .masking(Masking.responseBody())
        .process(InternalProcessingRequested, Lamp, newEntity())
        .build("X", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("masks the body of its response, so it can't be deduplicated");
  }

  /// The first response sets a cookie, which is masked in the event log, so a retry can't be given it
  @Test
  public void whenRetryOfARequestWhoseResponseIsMaskedThenConflict() {
    var bell = bell(false);
    var request = HttpInboxRequest.matching(m -> m.requestLine().matches("PUT /bell/.*")).noContent()
        .deduplicateOn(parseMessageId("PUT /bell/(.*)", 1))
        .process(BellRung, bell, newEntity())
        .response(Rang, _ -> new HttpResponse<>(200, "OK", Map.of("Set-Cookie", "session=s3cret"), null))
        .build("CookieRequest", UUID.fromString("9b1d7f3a-5c2e-4a86-b4d0-e8f6a2c4b917"));
    var cookieInbox = HttpInbox.of(List.of(request));
    var machine = Init.stateMachine(UUID.randomUUID().toString(), cookieInbox.entityModels());
    var message = new HttpRequest<byte[]>(PUT, URI.create("/bell/" + UUID.randomUUID()), Map.of(), null);
    Function<HttpRequest<byte[]>, HttpResponse<byte[]>> send = m -> HttpResponse.raw(machine.onEvent(UUID.randomUUID().toString(), cookieInbox.trigger(), m)
        .skip(1).next().block(Duration.ofSeconds(5)).getUnmarshalledData());

    HttpResponse<byte[]> first = send.apply(message);
    assertEquals(200, first.statusCode());
    assertEquals("session=s3cret", first.headerValue("Set-Cookie"));
    HttpResponse<byte[]> retry = send.apply(message);
    assertEquals(409, retry.statusCode());
    assertEquals(null, retry.headerValue("Set-Cookie"));
  }

  @Test
  public void whenRetryDiffersOnlyInWhatIsMaskedThenItGetsTheFirstResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> first = HttpResponse.raw(onRequest(inbox.trigger(), maskedRequest(messageId, "c0de", "t0ken")).getUnmarshalledData());
    HttpResponse<byte[]> retry = HttpResponse.raw(onRequest(inbox.trigger(), maskedRequest(messageId, "0ther", "refreshed")).getUnmarshalledData());
    assertEquals(201, retry.statusCode());
    assertArrayEquals(first.body(), retry.body());
  }

  @Test
  public void whenRequestIsNotStoredThenNeitherIsItsRouting() {
    Event<?> response = onRequest(PUT, URI.create("/not-stored/" + UUID.randomUUID()), asJson(new InternalProcess("Hello", 1)));
    HttpResponse<byte[]> message = HttpResponse.raw(response.getUnmarshalledData());
    assertEquals(201, message.statusCode());
    assertTrue(new String(message.body()).contains("Light is on!"));
    assertEquals(null, entityModelId(response.entityId()), "The routing is not stored");
  }

  @Test
  public void notStoredRequestCantBeDeduplicated() {
    assertThatThrownBy(() -> withBody("PUT .*/x/.*")
        .notStored()
        .deduplicateOn(parseMessageId("PUT .*/x/(.*)", 1))
        .process(InternalProcessingRequested, Lamp, newEntity())
        .build("X", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is not stored, so it can't be deduplicated");
  }

  private static UUID entityModelId(UUID entityId) {
    return Init.client().sql("SELECT EntityModelId FROM \"" + schema + "\".\"Entity\" WHERE EntityId=:entityId")
        .name("Test.EntityModel")
        .bind("entityId", entityId)
        .map(row -> row.get("EntityModelId", UUID.class))
        .one()
        .block();
  }

  @Test
  public void whenInternalProcessRequestOverHttpThenInternalProcessResponse() throws IOException {
    DisposableServer listener = new HttpInboxListener(stateMachine, inbox).listen(0);
    try {
      HttpResponse<byte[]> response = new NettyHttpClient(new NettyHttpClientBuilder().build())
          .exchange(new HttpRequest<>(
              PUT,
              URI.create("http://localhost:" + listener.port() + "/internal/" + UUID.randomUUID()),
              Map.of(),
              new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
          ))
          .block(Duration.ofSeconds(5));
      assertEquals(201, response.statusCode());
      assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
    } finally {
      listener.disposeNow();
    }
  }

  @Test
  public void whenCorrelationIdHeaderOverHttpThenResponseHasSameCorrelationId() throws IOException {
    HttpResponse<byte[]> response = exchangeOverHttp(new HttpInboxListener(stateMachine, inbox), Map.of("X-Correlation-Id", "abc-123"));
    assertEquals("abc-123", response.headerValue("X-Correlation-Id"));
  }

  @Test
  public void whenCustomCorrelationIdHeaderOverHttpThenResponseHasSameCorrelationId() throws IOException {
    HttpResponse<byte[]> response = exchangeOverHttp(
        new HttpInboxListener(stateMachine, inbox).correlationIdHeader("TID"),
        Map.of("TID", "tid-123", "X-Correlation-Id", "ignored")
    );
    assertEquals("tid-123", response.headerValue("X-Correlation-Id"));
  }

  @Test
  public void whenNoCorrelationIdHeaderOverHttpThenGeneratedCorrelationId() throws IOException {
    HttpResponse<byte[]> response = exchangeOverHttp(new HttpInboxListener(stateMachine, inbox), Map.of());
    assertThat(response.headerValue("X-Correlation-Id")).matches("SYS-\\d{19}");
  }

  private HttpResponse<byte[]> exchangeOverHttp(HttpInboxListener inboxListener, Map<String, String> headers) throws IOException {
    DisposableServer listener = inboxListener.listen(0);
    try {
      HttpResponse<byte[]> response = new NettyHttpClient(new NettyHttpClientBuilder().build())
          .exchange(new HttpRequest<>(
              PUT,
              URI.create("http://localhost:" + listener.port() + "/internal/" + UUID.randomUUID()),
              headers,
              new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
          ))
          .block(Duration.ofSeconds(5));
      assertEquals(201, response.statusCode());
      return response;
    } finally {
      listener.disposeNow();
    }
  }

  /// A function of the inbox request which throws, here the normalizer comparing a retried request with the first one,
  /// fails the transition, and the request is answered with 500 Internal Server Error
  @Test
  public void whenNormalizerThrowsOnRetriedRequestOverHttpThenInternalServerError() {
    DisposableServer listener = new HttpInboxListener(stateMachine, inbox).listen(0);
    try {
      var client = new NettyHttpClient(new NettyHttpClientBuilder().build());
      URI uri = URI.create("http://localhost:" + listener.port() + "/normalized/" + UUID.randomUUID());
      HttpResponse<byte[]> first = client.exchange(new HttpRequest<>(PUT, uri, Map.of("X-Version", "1"), "Hello!".getBytes()))
          .block(Duration.ofSeconds(5));
      assertEquals(201, first.statusCode());
      HttpResponse<byte[]> retried = client.exchange(new HttpRequest<>(PUT, uri, Map.of(), "Hello!".getBytes()))
          .block(Duration.ofSeconds(5));
      assertEquals(500, retried.statusCode());
    } finally {
      listener.disposeNow();
    }
  }

  @Test
  public void whenFilterRewritesRequestOverHttpThenRewrittenRequestIsRouted() throws IOException {
    DisposableServer listener = new HttpInboxListener(stateMachine, inbox)
        .filter((request, next) -> next.apply(new HttpRequest<>(
            PUT,
            URI.create(request.uri().getPath().replace("/rewritten/", "/internal/")),
            request.headers(),
            request.body()
        )))
        .listen(0);
    try {
      HttpResponse<byte[]> response = new NettyHttpClient(new NettyHttpClientBuilder().build())
          .exchange(new HttpRequest<>(
              PUT,
              URI.create("http://localhost:" + listener.port() + "/rewritten/" + UUID.randomUUID()),
              Map.of(),
              new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
          ))
          .block(Duration.ofSeconds(5));
      assertEquals(201, response.statusCode());
    } finally {
      listener.disposeNow();
    }
  }

  @Test
  public void whenFilterRespondsOverHttpThenLaterFiltersAndInboxAreSkipped() {
    List<String> applied = new ArrayList<>();
    DisposableServer listener = new HttpInboxListener(stateMachine, inbox)
        .filter((request, next) -> {
          applied.add("first");
          return next.apply(request);
        })
        .filter((_, _) -> {
          applied.add("second");
          return Mono.just(new HttpResponse<>(401, "Unauthorized"));
        })
        .filter((request, next) -> {
          applied.add("third");
          return next.apply(request);
        })
        .listen(0);
    try {
      HttpResponse<byte[]> response = new NettyHttpClient(new NettyHttpClientBuilder().build())
          .exchange(new HttpRequest<>(
              PUT,
              URI.create("http://localhost:" + listener.port() + "/internal/" + UUID.randomUUID()),
              Map.of(),
              "{}".getBytes()
          ))
          .block(Duration.ofSeconds(5));
      assertEquals(401, response.statusCode());
      assertEquals(List.of("first", "second"), applied);
    } finally {
      listener.disposeNow();
    }
  }

  @Test
  public void whenZeroingInternalProcessRequestThenAcceptedResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
    );
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());

    Event<?> zeroResponseEvent = onRequest(PUT, URI.create("/zero/" + entityId), null);
    HttpResponse<byte[]> zeroResponse = HttpMessageParser.parseResponse(zeroResponseEvent.data());
    assertEquals(201, zeroResponse.statusCode());
    assertEquals("Complete", ProblemDetail.parse(zeroResponse.body()).detail());
  }

  @Test
  public void whenRequestWithoutMessageIdIsRepeatedOnSameEntityThenItIsProcessedAgain() throws IOException {
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(onRequest(
        PUT,
        URI.create("/internal/" + UUID.randomUUID()),
        new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
    ).data());
    UUID entityId = UUID.fromString(ProblemDetail.parse(response.body()).entityId());

    HttpResponse<byte[]> zero1 = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/zero/" + entityId), null).data());
    HttpResponse<byte[]> zero2 = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/zero/" + entityId), null).data());
    assertEquals(201, zero1.statusCode());
    assertEquals(201, zero2.statusCode());
    assertEquals("Complete", ProblemDetail.parse(zero2.body()).detail());
    // A response of its own, not the one of the first request
    assertFalse(Arrays.equals(zero1.body(), zero2.body()));
  }

  @Test
  public void whenCancelInternalProcessRequestThen() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(
        PUT,
        URI.create("/internal/" + messageId),
        new ObjectMapper().writeValueAsBytes(new InternalProcess("Hello", 1))
    );
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    ProblemDetail problemDetail = ProblemDetail.parse(response.body());
    assertTrue(problemDetail.detail().startsWith("Light is on!"));
    UUID entityId = UUID.fromString(problemDetail.entityId());
    Event<?> cancelResponseEvent = onRequest(DELETE, URI.create("/internal/" + entityId), null);
    HttpResponse<byte[]> cancelResponse = HttpMessageParser.parseResponse(cancelResponseEvent.data());
    assertEquals(201, cancelResponse.statusCode());
  }

  @Test
  public void whenComplexInternalProcessRequestThenComplexInternalProcessResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/complexinternal/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Phew! Light is switched on!"));
  }

  @Test
  public void whenRequestIsRejectedDownstreamThenUnprocessableEntityResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(422, response.statusCode());
    ProblemDetail pd = ProblemDetail.parse(response.body());
    assertThat(pd.detail()).matches("\\[Always rejected event submitted] on \\[Lamp]/.{36} rejected for state \\[Begin].*");
    assertEquals(422, pd.status());
  }

  @Test
  public void whenExternalProcessRequestThenExternalProcessResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/external/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  public void whenSubsystemUnavailableThenInternalServerError() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/unavailable/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(500, response.statusCode());
    assertEquals("Subsystem unavailable", ProblemDetail.parse(response.body()).detail());
  }

  /// A response of 500 is an error for monitoring
  @Test
  public void failedRequestIsCompletedWithSeverityError() {
    TransitionModel<?, ?> completion = requests.getFirst().transitions().get(HttpInbox.States.Dispatched).stream()
        .filter(transition -> transition.eventType() == FailedRequestCompleted)
        .findFirst()
        .orElseThrow();
    assertEquals(Severity.Error, completion.severity());
  }

  @Test
  public void whenNoBearerTokenThenUnauthorizedWithChallenge() throws IOException {
    Event<?> responseEvent = onRequest(PUT, URI.create("/protected/" + UUID.randomUUID()), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(401, response.statusCode());
    assertEquals("Bearer", response.headerValue("WWW-Authenticate"));
    assertEquals("Authorization header missing", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  public void whenInvalidBearerTokenThenUnauthorizedWithInvalidToken() throws IOException {
    Event<?> responseEvent = onRequest(inbox.trigger(), new HttpRequest<>(
        PUT,
        URI.create("/protected/" + UUID.randomUUID()),
        Map.of("Authorization", "Bearer not-a-token"),
        "Hello!".getBytes()
    ));
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(401, response.statusCode());
    assertEquals(
        "Bearer error=\"invalid_token\", error_description=\"Bearer token is invalid or expired\"",
        response.headerValue("WWW-Authenticate")
    );
  }

  @Test
  public void whenUnknownIdThenBadRequest() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/handledunknown/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(400, response.statusCode());
    assertEquals("No such entity", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  @Disabled("Sync response (in request/reply session) not possible with reversals triggered by the resolver")
  public void whenLongExternalProcessRequestThenRollback() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onRequest(PUT, URI.create("/long-external/" + messageId), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is externally switched on!"));
  }

  @Test
  @Disabled("TODO")
  public void whenOneWayRequestThenTimeout() throws IOException {
    Event<?> responseEvent = onRequest(PUT, URI.create("/oneway/xxx"), "Hello!".getBytes());
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertEquals(201, response.statusCode());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
  }

  @Test
  public void whenDuplicateRequestThenRespondWithSameResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    InternalProcess body = new InternalProcess("Static", 0);
    HttpResponse<byte[]> response1 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response1.statusCode());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponse<byte[]> response2 = HttpMessageParser.parseResponse(onInternalRequest(messageId, body).data());
    assertEquals(201, response2.statusCode());
    assertArrayEquals(response1.body(), response2.body());
  }

  @Test
  public void whenNonDuplicateRequestWithSameMessageIdThenRespondWithBadRequest() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> response1 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("A", 1)
    ).data());
    assertTrue(ProblemDetail.parse(response1.body()).detail().startsWith("Light is on!"));
    HttpResponse<byte[]> response2 = HttpMessageParser.parseResponse(onInternalRequest(
        messageId,
        new InternalProcess("B", 2)
    ).data());
    assertEquals(400, response2.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(response2.body()).detail());
  }

  @Test
  public void whenRollbackWithMessageIdInContentThenRolledBack() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> request = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/reversible/" + messageId), asJson(new InternalProcess("Hello!", 1))).data());
    assertEquals(201, request.statusCode());
    HttpResponse<byte[]> rollback = HttpMessageParser.parseResponse(onRequest(POST, URI.create("/reversals"), asJson(new Reversal(messageId))).data());
    assertEquals(201, rollback.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollback.body()).detail());
  }

  @Test
  public void whenRollbackWithInvalidContentThenBadRequest() throws IOException {
    HttpResponse<byte[]> rollback = HttpMessageParser.parseResponse(onRequest(POST, URI.create("/reversals"), "not json".getBytes()).data());
    assertEquals(400, rollback.statusCode());
    assertTrue(ProblemDetail.parse(rollback.body()).detail().startsWith("Invalid request body"));
  }

  @Test
  public void whenRollbackWithoutMessageIdInContentThenBadRequest() throws IOException {
    HttpResponse<byte[]> rollback = HttpMessageParser.parseResponse(onRequest(POST, URI.create("/reversals"), asJson(new Reversal(null))).data());
    assertEquals(400, rollback.statusCode());
    assertEquals("Unable to parse message id: messageId missing", ProblemDetail.parse(rollback.body()).detail());
  }

  @Test
  public void whenRollbackAfterRequestCompletedThenRespondOk() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 100));
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    assertTrue(ProblemDetail.parse(response.body()).detail().startsWith("Light is on!"));
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponse<byte[]> rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollbackResponse.body()).detail());
  }

  @Test
  public void whenRollbackBeforeRequestThenRespondOk() throws IOException {
    String messageId = UUID.randomUUID().toString();
    Event<?> rollbackResponseEvent = onRollbackRequest(messageId);
    HttpResponse<byte[]> rollbackResponse = HttpMessageParser.parseResponse(rollbackResponseEvent.data());
    assertEquals(201, rollbackResponse.statusCode());
    assertEquals("Nothing to roll back", ProblemDetail.parse(rollbackResponse.body()).detail());

    // Verify that a request coming in afterward is rejected (422)
    Event<?> responseEvent = onInternalRequest(messageId, new InternalProcess("Hello!", 123));
    HttpResponse<byte[]> response = HttpMessageParser.parseResponse(responseEvent.data());
    var detail = ProblemDetail.parse(response.body()).detail();
    assertEquals(422, response.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(response.body()).detail());
  }

  /// The same process event is triggered by two inbox requests, and the response triggered on the group goes to the
  /// inbox request of the session
  @Test
  public void responseOnRequestGroupGoesToInboxRequestOfSession() throws IOException {
    HttpResponse<byte[]> internal = HttpMessageParser.parseResponse(onInternalRequest(UUID.randomUUID().toString(), new InternalProcess("A", 1)).data());
    assertEquals(201, internal.statusCode());
    HttpResponse<byte[]> other = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/other-internal/" + UUID.randomUUID()), null).data());
    assertEquals(201, other.statusCode());
    assertTrue(ProblemDetail.parse(other.body()).detail().startsWith("Light is on!"));
  }

  /// Another inbox has a routing model of its own, with the id of the one the state machine runs, which may route to
  /// other requests
  @Test
  public void listenerOfAnotherInboxIsRejected() {
    assertThatThrownBy(() -> new HttpInboxListener(stateMachine, HttpInbox.of(requests)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("doesn't run the request routing of the inbox");
  }

  /// The inbox asks for no transitions until its entity models are asked for, so it may be a constant declared before
  /// what the transitions refer to
  @Test
  public void inboxAsksForNoTransitionsWhenCreated() {
    var notYet = EntityModel.of("NotYet", UUID.randomUUID(), () -> {
      throw new IllegalStateException("Transitions asked for");
    });
    var request = HttpInboxRequest.matching(m -> m.requestLine().matches("PUT /not-yet")).noContent()
        .process(BellRung, notYet, newEntity())
        .build("NotYetRequest", UUID.randomUUID());
    var inbox = HttpInbox.of(List.of(request));
    assertThat(inbox.requests()).containsExactly(request);
    assertThatThrownBy(inbox::entityModels).hasMessage("Transitions asked for");
  }

  @Test
  public void listenerOfAStateMachineWithoutAnInboxIsRejected() throws Exception {
    // With a database client of its own, which closing it closes
    try (var withoutInbox = new StateMachine(_ -> Mono.empty(), List.of(Chime), Init.database(), Init.newClient(),
        Init.migrationDataSource(), UUID.randomUUID().toString(), "Test", Clock.systemUTC(), null, false)) {
      assertThatThrownBy(() -> new HttpInboxListener(withoutInbox, inbox))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("doesn't run the request routing of the inbox");
    }
  }

  @Test
  public void requestGroupIncludesInboxRequestsOnly() {
    assertTrue(Request.includes(InternalRequest));
    assertFalse(Request.includes(Lamp));
    assertFalse(Request.includes(inbox.requestRouting()));
  }

  /// A group has no entities of its own, so it gets no event types, while each inbox request has the response event
  /// types triggered on the group
  @Test
  public void eventTypesAreRegisteredForInboxRequestsNotForTheGroup() {
    var eventTypes = EventTypes.eventTypesByEntityModel(stateMachine.entityModels());
    assertFalse(eventTypes.containsKey(Request));
    for (HttpInboxRequest<?> request : requests)
      assertThat(eventTypes.get(request)).contains(RequestCompleted, FailedRequestCompleted, InvalidRequestCompleted);
  }

  @Test
  public void entityCanNotBeCreatedInGroup() {
    var trigger = new EventTrigger<HttpRequest<byte[]>, HttpRequest<byte[]>, Void>(
        new EventTrigger.EventSpec<>(HttpInbox.RequestReceived, d -> d),
        List.of(newEntityId()),
        Request,
        true
    );
    assertThatThrownBy(() -> stateMachine.onEvent(UUID.randomUUID().toString(), trigger, new HttpRequest<>(PUT, URI.create("/x"), Map.of(), null)).blockLast(Duration.ofSeconds(5)))
        .hasMessageContaining("can't be created in the group Request");
  }

  @Test
  public void entityOfGroupCanOnlyBeIdentifiedBySession() {
    var trigger = new EventTrigger<HttpRequest<byte[]>, HttpRequest<byte[]>, Void>(
        new EventTrigger.EventSpec<>(HttpInbox.RequestReceived, d -> d),
        List.of(_ -> EntitySelector.entityId(UUID.randomUUID())),
        Request,
        false
    );
    assertThatThrownBy(() -> stateMachine.onEvent(UUID.randomUUID().toString(), trigger, new HttpRequest<>(PUT, URI.create("/x"), Map.of(), null)).blockLast(Duration.ofSeconds(5)))
        .hasMessageContaining("can only be identified by the session");
  }

  @Test
  public void whenContentMatchesProcessThenItsEventIsTriggered() throws IOException {
    HttpResponse<byte[]> internal = onContentRequest(UUID.randomUUID().toString(), "internal");
    assertEquals(201, internal.statusCode());
    assertTrue(ProblemDetail.parse(internal.body()).detail().startsWith("Light is on!"));
    HttpResponse<byte[]> unavailable = onContentRequest(UUID.randomUUID().toString(), "unavailable");
    assertEquals(500, unavailable.statusCode());
    assertEquals("Subsystem unavailable", ProblemDetail.parse(unavailable.body()).detail());
  }

  /// The processes of an endpoint have their own input and selector: `zero` switches the existing lamp of the path
  @Test
  public void whenContentMatchesProcessThenItsInputAndSelectorAreUsed() throws IOException {
    HttpResponse<byte[]> created = onContentRequest(UUID.randomUUID().toString(), "internal");
    UUID lamp = UUID.fromString(ProblemDetail.parse(created.body()).entityId());
    HttpResponse<byte[]> zeroed = onContentRequest(lamp.toString(), "zero");
    assertEquals(201, zeroed.statusCode());
    assertEquals("Complete", ProblemDetail.parse(zeroed.body()).detail());
  }

  @Test
  public void whenContentMatchesNoProcessThenBadRequest() throws IOException {
    HttpResponse<byte[]> response = onContentRequest(UUID.randomUUID().toString(), "other");
    assertEquals(400, response.statusCode());
    assertEquals("No process for the content", ProblemDetail.parse(response.body()).detail());
  }

  @Test
  public void diagramOfContentRequestShowsTheChoiceOfProcess() throws IOException {
    File file = new PlantUMLFormatter(ContentRequest).formatToImage(Files.createTempDirectory("inbox").toString());
    assertThat(Files.readString(file.toPath()))
        .contains("Request dispatched [a == internal]", "Request dispatched [a == unavailable]", "Request dispatched [a == zero]");
  }

  @Test
  public void processWithoutContentPredicateMustBeLast() {
    assertThatThrownBy(() -> HttpInboxRequest.matching(_ -> true).noContent()
        .process(InternalProcessingRequested, Lamp, newEntity())
        .process(when("any", _ -> true), LampSwitchedOn, Lamp, newEntity())
        .build("Invalid", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be the last one");
  }

  @Test
  public void processesOfRequestWithRollbackMustHaveTheSameEntityModel() {
    assertThatThrownBy(() -> HttpInboxRequest.matching(_ -> true).noContent()
        .deduplicateOn(parseMessageId("(.*)", 1))
        .process(when("one", _ -> true), InternalProcessingRequested, Lamp, newEntity())
        .process(InternalProcessingRequested, EntityModel.of("Other", UUID.randomUUID(), Map::of), newEntity())
        .rollback(Rollback.matching(_ -> false).noContent().deduplicateOn(parseMessageId("(.*)", 1)))
        .build("Invalid", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must have the same entity model");
  }

  /// A client using the message id of a request of one type for a request of another type gets 400 Conflict, as for
  /// another request of the same type
  @Test
  public void whenSameMessageIdForAnotherRequestTypeThenConflict() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> first = HttpMessageParser.parseResponse(onInternalRequest(messageId, new InternalProcess("A", 1)).data());
    assertEquals(201, first.statusCode());
    HttpResponse<byte[]> second = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/complexinternal/" + messageId), "Hello!".getBytes()).data());
    assertEquals(400, second.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(second.body()).detail());
  }

  /// The first request with the message id was rejected by the application, so it has no accepted request to compare with
  @Test
  public void whenSameMessageIdAsRejectedRequestOfAnotherTypeThenConflict() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> first = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!".getBytes()).data());
    assertEquals(422, first.statusCode());
    HttpResponse<byte[]> second = HttpMessageParser.parseResponse(onInternalRequest(messageId, new InternalProcess("A", 1)).data());
    assertEquals(400, second.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(second.body()).detail());
  }

  @Test
  public void whenRejectedRequestIsRepeatedThenRespondWithSameResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> first = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!".getBytes()).data());
    assertEquals(422, first.statusCode());
    HttpResponse<byte[]> second = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/rejected/" + messageId), "Hello!".getBytes()).data());
    assertEquals(422, second.statusCode());
    assertArrayEquals(first.body(), second.body());
  }

  private HttpResponse<byte[]> onContentRequest(String id, String a) throws IOException {
    return HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/content/" + id), asJson(new InternalProcess(a, 0))).data());
  }

  @Test
  public void whenRollbackBeforeRequestIsRepeatedThenRespondWithSameResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> first = HttpMessageParser.parseResponse(onRollbackRequest(messageId).data());
    assertEquals("Nothing to roll back", ProblemDetail.parse(first.body()).detail());
    // A retry, with another correlation id, which the normalizer removes
    HttpResponse<byte[]> second = onRollbackRequest(messageId, Map.of("X-Correlation-Id", "retry"));
    assertEquals(201, second.statusCode());
    assertArrayEquals(first.body(), second.body());
  }

  /// A repeated rollback with the same message id which differs from the first after normalizing is another rollback,
  /// not a retry, as for requests
  @Test
  public void whenAnotherRollbackOfRolledBackRequestThenConflict() throws IOException {
    String messageId = UUID.randomUUID().toString();
    onInternalRequest(messageId, new InternalProcess("Hello!", 100));
    HttpResponse<byte[]> first = onRollbackRequest(messageId, Map.of("X-Reason", "Cancelled"));
    assertEquals("Rolled back", ProblemDetail.parse(first.body()).detail());
    HttpResponse<byte[]> second = onRollbackRequest(messageId, Map.of("X-Reason", "Timed out"));
    assertEquals(400, second.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(second.body()).detail());
  }

  /// Without a normalizer a rollback is compared as it is, so a retry with another correlation id is another rollback
  @Test
  public void whenRollbackWithoutNormalizerIsRetriedWithAnotherCorrelationIdThenConflict() throws IOException {
    String messageId = UUID.randomUUID().toString();
    onRequest(PUT, URI.create("/owned/" + messageId), asJson(new InternalProcess("alice", 1)));
    HttpResponse<byte[]> first = onOwnedRollback(messageId, Map.of("X-Owner", "alice", "X-Correlation-Id", "first"));
    assertEquals("Rolled back", ProblemDetail.parse(first.body()).detail());
    HttpResponse<byte[]> second = onOwnedRollback(messageId, Map.of("X-Owner", "alice", "X-Correlation-Id", "retry"));
    assertEquals(400, second.statusCode());
    assertEquals("Conflict", ProblemDetail.parse(second.body()).detail());
  }

  @Test
  public void whenRollbackAfterRequestIsRepeatedThenRespondWithSameResponse() throws IOException {
    String messageId = UUID.randomUUID().toString();
    onInternalRequest(messageId, new InternalProcess("Hello!", 100));
    HttpResponse<byte[]> first = HttpMessageParser.parseResponse(onRollbackRequest(messageId).data());
    assertEquals("Rolled back", ProblemDetail.parse(first.body()).detail());
    HttpResponse<byte[]> second = HttpMessageParser.parseResponse(onRollbackRequest(messageId).data());
    assertEquals(201, second.statusCode());
    assertArrayEquals(first.body(), second.body());
  }

  @Test
  public void whenRollbackOfRequestAuthorizedByBodyThenRollbackIsAuthorizedByItsOwnAuthorizer() throws IOException {
    String messageId = UUID.randomUUID().toString();
    HttpResponse<byte[]> request = HttpMessageParser.parseResponse(onRequest(PUT, URI.create("/owned/" + messageId), asJson(new InternalProcess("alice", 1))).data());
    assertEquals(201, request.statusCode());
    HttpResponse<byte[]> rollback = onOwnedRollback(messageId, Map.of("X-Owner", "alice"));
    assertEquals(201, rollback.statusCode());
    assertEquals("Rolled back", ProblemDetail.parse(rollback.body()).detail());
  }

  @Test
  public void whenRollbackIsRefusedByItsAuthorizerThenRefusalResponse() throws IOException {
    HttpResponse<byte[]> rollback = onOwnedRollback(UUID.randomUUID().toString(), Map.of());
    assertEquals(400, rollback.statusCode());
    assertEquals("Owner missing", ProblemDetail.parse(rollback.body()).detail());
  }

  /// A message id is the authorized subject's, so another subject's rollback has nothing to roll back
  @Test
  public void whenRollbackByAnotherSubjectThenNothingToRollBack() throws IOException {
    String messageId = UUID.randomUUID().toString();
    onRequest(PUT, URI.create("/owned/" + messageId), asJson(new InternalProcess("alice", 1)));
    HttpResponse<byte[]> rollback = onOwnedRollback(messageId, Map.of("X-Owner", "bob"));
    assertEquals(201, rollback.statusCode());
    assertEquals("Nothing to roll back", ProblemDetail.parse(rollback.body()).detail());
  }

  @Test
  public void rollbackOfRequestWithAuthorizerNeedsAnAuthorizer() {
    assertThatThrownBy(() -> HttpInboxRequest.matching(_ -> true).noContent()
        .deduplicateOn(parseMessageId("(.*)", 1))
        .authorizer((_, _) -> Authorization.authorized("alice"))
        .process(InternalProcessingRequested, Lamp, newEntity())
        .rollback(Rollback.matching(_ -> false).noContent().deduplicateOn(parseMessageId("(.*)", 1)))
        .build("Invalid", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("needs an authorizer");
  }

  @Test
  public void rollbackOfRequestWithoutAuthorizerCanNotHaveAnAuthorizer() {
    assertThatThrownBy(() -> HttpInboxRequest.matching(_ -> true).noContent()
        .deduplicateOn(parseMessageId("(.*)", 1))
        .process(InternalProcessingRequested, Lamp, newEntity())
        .rollback(Rollback.matching(_ -> false).noContent().deduplicateOn(parseMessageId("(.*)", 1)).authorizer((_, _) -> Authorization.authorized("alice")))
        .build("Invalid", UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("can't have an authorizer");
  }

  static final EntityModel Chime = EntityModel.of("Chime", UUID.fromString("b8d3f6a1-2e47-4c09-9b5a-6f1c8e4d2a73"), HttpInboxTest::chimeTransitions);
  static final EventType<Void, Void> BellRung = BasicEventType.of("Bell rung", UUID.fromString("d6f2a9c4-1b85-4e37-8a0d-3c7e5f9b1a26"));
  static final EventType<Void, Void> ChimeRung = BasicEventType.of("Chime rung", UUID.fromString("7a4c2e9f-5d18-4b63-b1e0-8f6d3a2c9e54"));
  static final ResponseEventType<String> Rang = new ResponseEventType<>(
      "Rang",
      UUID.fromString("2f9b6d3e-8c41-4a75-9e0b-5d7a1c4f8e62"),
      DataType.string()
  );

  static final EventType<Void, Void> BellPolished = BasicEventType.of("Bell polished", UUID.fromString("5e8a3c1f-9b26-4d70-a4e3-1c7f2b9d6e08"));
  static final EventType<Void, Void> BellScheduled = BasicEventType.of("Bell scheduled", UUID.fromString("c9f1b4e7-3a62-4d8c-8e05-7b2d6a9c1f34"));

  /// A bell, which responds itself, or through the chime it rings, when it is rung. When it is polished it gives no
  /// response, and when it is scheduled it is rung later, in another session.
  private static EntityModel bell(boolean throughChime) {
    return EntityModel.of("Bell", UUID.fromString("4c1e8a2f-7d36-4b95-a0f8-9e2d5b7c3a16"), () -> Map.of(Begin, List.of(
        throughChime ?
            onEvent(BellRung).toSelf().trigger(ChimeRung).on(Chime).identifiedBy(newEntityId()).output() :
            onEvent(BellRung).toSelf().trigger(Rang).with(_ -> "Ding").on(Request).identifiedBy(entityIdFromSession()).output(),
        onEvent(BellPolished).toSelf().output(),
        onEvent(BellScheduled).toSelf().assembleInput().trigger(BellRung).after(_ -> Duration.ofMinutes(1)).output()
    )));
  }

  private static Map<State, List<TransitionModel<?, ?>>> chimeTransitions() {
    return Map.of(Begin, List.of(
        onEvent(ChimeRung).toSelf().trigger(Rang).with(_ -> "Dong").on(Request).identifiedBy(entityIdFromSession()).output()
    ));
  }

  private static HttpInboxRequest.Builder<Void> bellRequest(EntityModel bell) {
    return HttpInboxRequest.matching(m -> m.requestLine().matches("PUT /bell")).noContent().process(BellRung, bell, newEntity());
  }

  @Test
  public void responseGivenByProcessMustBeTakenByTheRequest() {
    var bell = bell(false);
    var request = bellRequest(bell).build("BellRequest", UUID.randomUUID());
    assertThatThrownBy(() -> HttpInbox.of(List.of(request)).entityModels())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("The inbox request BellRequest doesn't take the response Rang, which Bell rung on Bell triggers on Request");
  }

  @Test
  public void responseGivenByEntityModelTheProcessTriggersMustBeTakenByTheRequest() {
    var bell = bell(true);
    var request = bellRequest(bell).build("BellRequest", UUID.randomUUID());
    assertThatThrownBy(() -> HttpInbox.of(List.of(request)).entityModels())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("The inbox request BellRequest doesn't take the response Rang, which Chime rung on Chime triggers on Request");
  }

  /// Two requests on the bell, where only ringing it gives a custom response: only the request ringing it takes it
  @Test
  public void requestTakesOnlyTheResponsesOfItsOwnProcesses() {
    var bell = bell(true);
    var ring = bellRequest(bell).response(Rang, _ -> new HttpResponse<>(200, "OK")).build("RingRequest", UUID.randomUUID());
    var polish = HttpInboxRequest.matching(m -> m.requestLine().matches("PUT /bell/polish")).noContent()
        .process(BellPolished, bell, newEntity())
        .build("PolishRequest", UUID.randomUUID());
    assertThat(EntityModel.reachable(HttpInbox.of(List.of(polish, ring)).entityModels())).contains(ring, polish, bell, Chime);
  }

  /// Ringing the bell later is in another session, which the check doesn't follow
  @Test
  public void delayedTriggerIsNotFollowed() {
    var bell = bell(false);
    var schedule = HttpInboxRequest.matching(m -> m.requestLine().matches("PUT /bell/schedule")).noContent()
        .process(BellScheduled, bell, newEntity())
        .build("ScheduleRequest", UUID.randomUUID());
    assertThat(EntityModel.reachable(HttpInbox.of(List.of(schedule)).entityModels())).contains(schedule, bell);
  }

  @Test
  public void responseTakenByTheRequestPassesTheCheck() {
    var bell = bell(true);
    var request = bellRequest(bell).response(Rang, _ -> new HttpResponse<>(200, "OK")).build("BellRequest", UUID.randomUUID());
    // The chime is reached only through the bell
    assertThat(EntityModel.reachable(HttpInbox.of(List.of(request)).entityModels())).contains(request, bell, Chime);
  }

  private HttpResponse<byte[]> onOwnedRollback(String messageId, Map<String, String> headers) throws IOException {
    return HttpMessageParser.parseResponse(onRequest(inbox.trigger(), new HttpRequest<>(
        DELETE,
        URI.create("/owned/messages/" + messageId),
        headers,
        null
    )).data());
  }

  @Test
  public void formatModel() throws IOException {
    System.out.println(new PlantUMLFormatter(HttpInbox.of(requests).requestRouting()).formatToImage("/tmp"));
    System.out.println(new PlantUMLFormatter(InternalRequest).formatToImage("/tmp"));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ProblemDetail(String type, String title, int status, String detail, String entityId) {

    static ProblemDetail parse(byte[] body) throws IOException {
      var reader = new ObjectMapper().readerFor(ProblemDetail.class);
      return reader.readValue(body);
    }

  }

  private Event<?> onInternalRequest(String messageId, InternalProcess body) {
    return onRequest(PUT, URI.create("/internal/" + messageId), asJson(body));
  }

  private static AccessTokenVerifier verifier() {
    try {
      return new AccessTokenVerifier(
          new JWKSet(new RSAKeyGenerator(2048).keyID("test").generate()).toPublicJWKSet().toString(),
          URI.create("https://idp.example.com"),
          null,
          Clock.systemUTC()
      );
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> byte[] asJson(T value) {
    return new ObjectMapper().writeValueAsBytes(value);
  }

  private Event<?> onRequest(Method method, URI uri, byte[] body) {
    return onRequest(inbox.trigger(), new HttpRequest<>(method, uri, Map.of(), body));
  }

  private Event<?> onRollbackRequest(String messageId) {
    return onRequest(DELETE, URI.create("/lamps/messages/" + messageId), null);
  }

  private static HttpRequest<byte[]> withoutCorrelationId(HttpRequest<byte[]> m) {
    var headers = new HashMap<>(m.headers());
    headers.remove("X-Correlation-Id");
    return new HttpRequest<>(m.method(), m.uri(), headers, m.body());
  }

  private HttpResponse<byte[]> onRollbackRequest(String messageId, Map<String, String> headers) throws IOException {
    return HttpMessageParser.parseResponse(onRequest(inbox.trigger(), new HttpRequest<>(
        DELETE,
        URI.create("/lamps/messages/" + messageId),
        headers,
        null
    )).data());
  }

  private Event<?> onRequest(EventTrigger<HttpRequest<byte[]>, ?, ?> eventTrigger, HttpRequest<byte[]> requestMessage) {
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
