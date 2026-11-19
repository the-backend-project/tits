package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.BatchNumberClaimed;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedCapture;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedPreauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authentication;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Capture;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.CaptureRequestedTooLate;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthentication;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Preauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Refund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackPreauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.ApprovedCutOff;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.Reconciliation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.core.Tuples.Tuple6;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.Validated.Valid;
import com.github.thxmasj.backend.core.http.NettyHttpClient;
import com.github.thxmasj.backend.core.http.NettyHttpClientBuilder;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.outbox.AtLeastOnce;
import com.github.thxmasj.backend.http.outbox.AtLeastOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.AtMostOnce;
import com.github.thxmasj.backend.http.outbox.AtMostOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.BatchedAtMostOnceWithRollbackDispatcher;
import com.github.thxmasj.backend.http.outbox.Dispatcher;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import com.github.thxmasj.backend.core.message.http.TypedHttpResponse;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.Request;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.Response;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerAuthorisationRequest;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant.Location;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.ReversalData;
import com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CutOff;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// Verifies that a payment with automatic capture, followed by a cut-off request, results in a reconciliation request
/// being sent to the acquirer, and that a merchant can't be created with a merchant id another merchant has.
public class CutOffReconciliationTest {

  private static final String AGGREGATOR_ID = "aggregator";
  private static final DataSource migrationDataSource = Init.migrationDataSource();

  private static HttpServer acquirer;
  // The paths of the requests received by the acquirer and the merchant (they share the same test server)
  private static final ConcurrentLinkedQueue<String> receivedPaths = new ConcurrentLinkedQueue<>();
  // The input of the reconciliation request. It is dispatched by a conditional trigger, so the input has been stored in
  // and read back from the database.
  private static final AtomicReference<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>> reconciliationInput =
      new AtomicReference<>();

  @BeforeAll
  public static void startServer() throws IOException {
    acquirer = HttpServer.create(new InetSocketAddress(0), 10);
    acquirer.createContext("/", exchange -> {
      receivedPaths.add(exchange.getRequestURI().getPath().substring(1));
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
    });
    acquirer.start();
  }

  @AfterAll
  public static void stopServer() {
    acquirer.stop(0);
  }

  @Test
  public void reconciliationRequestIsSentToAcquirerAfterAutomaticCaptureAndCutOff() throws Exception {
    String merchantId = UUID.randomUUID().toString();
    var machine = stateMachine(merchantId);

    // Merchant. Creating it opens its first settlement (batch number 1), which the payment will be part of
    request(machine, "/merchants");

    // Payment request with automatic capture: authentication, authorisation (approved by the acquirer)
    request(machine, "/payments");
    await("payment authorisation to be sent to the acquirer", () -> receivedPaths.contains(authorisationPath()));
    // Give the acquirer response time to be processed, so the authorisation request has its final event
    Thread.sleep(3000);

    // Cut-off
    receivedPaths.clear();
    request(machine, "/cutoffs");

    await("reconciliation request to be sent to the acquirer", () -> receivedPaths.contains(Reconciliation.name()));
    assertTrue(receivedPaths.contains(Reconciliation.name()));

    // The input data of the conditional triggered reconciliation request survived the database round trip
    assertEquals(
        tuple(new BatchNumber(merchantId, 1), new AcquirerBatchNumber(merchantId, 2), merchant(merchantId)),
        reconciliationInput.get()
    );
  }

  @Test
  public void merchantWithMerchantIdTakenIsRejected() {
    var machine = stateMachine(UUID.randomUUID().toString());

    assertEquals(201, responseTo(request(machine, "/merchants")).statusCode());
    // Another request to create a merchant with the same merchant id
    assertEquals(400, responseTo(request(machine, "/merchants")).statusCode());
  }

  private static HttpResponseMessage responseTo(Event<?> event) {
    return (HttpResponseMessage) event.getUnmarshalledData();
  }

  private static String authorisationPath() {
    return PaymentTransitions.OutboxRequestModelIds.Authorisation.name();
  }

  private static void await(String description, java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) Thread.sleep(200);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description + ". Received: " + receivedPaths);
  }

  private static Event<?> request(Application application, String path) {
    var message = new HttpRequestMessage(POST, URI.create(path), Map.of(), "{}".getBytes());
    return application.machine().onEvent(UUID.randomUUID().toString(), application.inbox().trigger(), message)
        .switchIfEmpty(Mono.error(new RuntimeException("No response to " + path)))
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        .block(Duration.ofSeconds(15));
  }

  private static Merchant merchant(String merchantId) {
    return new Merchant(
        AGGREGATOR_ID,
        merchantId,
        "Test merchant",
        new Location("Street 1", "0150", "Oslo"),
        "5411",
        "acquirer",
        false
    );
  }

  /// The state machine of an application with the card payment template, and its inbox
  private record Application(StateMachine machine, HttpInbox inbox) {}

  private static Application stateMachine(String merchantId) {
    var merchant = merchant(merchantId);
    var authorisation = new Authorisation(
        merchantId,
        null,
        AGGREGATOR_ID,
        new Amount("NOK", 1000, null),
        "reference",
        true, // Automatic capture
        false,
        ZonedDateTime.now(),
        "authentication-data",
        null
    );
    var cutOff = new CutOff(merchantId, 1, AGGREGATOR_ID, "http://localhost");

    var cardPayments = new CardPayments(
        _ -> new PaymentToken("4111111111111111", "00", "3012"),
        CutOffReconciliationTest::authenticationDispatcher,
        p -> DummyTransitions.reversibleDispatcher(Preauthorisation, PreauthorisationRollbackOnAccepted, PreauthorisationRollbackOnUnknown, p),
        CutOffReconciliationTest::acquirerAuthorisationDispatcher,
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(Capture, p)),
        p -> DummyTransitions.reversibleDispatcher(Refund, RefundRollbackOnAccepted, RefundRollbackOnUnknown, p),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(CaptureRequestedTooLate, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedAuthentication, p)),
        _ -> dispatcher(Reconciliation, reconciliationInput::set),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(RolledBackAuthorisation, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(RolledBackPreauthorisation, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedPreauthorisation, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedAuthorisation, p)),
        _ -> dispatcher(ApprovedAuthorisation),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(DeclinedAuthorisation, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedCapture, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedRefund, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedRefund, p)),
        p -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(DeclinedRefund, p)),
        s -> DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedCutOff, s))
    );

    List<HttpInboxRequest<?>> requests = List.of(
        postRequest("/merchants")
            .process(MerchantEvent.MerchantCreated, cardPayments.merchant(), _ -> merchant, newEntity())
            .build("MerchantRequest", UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0001")),
        postRequest("/payments")
            .process(PaymentEvent.PaymentRequested, cardPayments.payment(), _ -> tuple(authorisation, new byte[0]), newEntity())
            .build("PaymentRequest", UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0002")),
        postRequest("/cutoffs")
            .process(SettlementEvent.CutOffRequested, cardPayments.settlement(), _ -> cutOff, (_, _) -> new Valid<>(idEvent(BatchNumberClaimed, new BatchNumber(merchantId, 1))))
            .build("CutOffRequest", UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0003"))
    );
    var inbox = HttpInbox.of(requests, cardPayments.entityModels());

    return new Application(
        new StateMachine(
            _ -> Mono.empty(),
            inbox.entityModels(),
            Init.database(),
            Init.client(),
            migrationDataSource,
            UUID.randomUUID().toString(),
            "Test",
            Clock.systemUTC(),
            new Logger("CutOffReconciliationTest"),
            true
        ),
        inbox
    );
  }

  /// The authentication of payments, made with the payment model
  private static Dispatcher<AuthenticationData, TypedHttpRequest<Request>> authenticationDispatcher(EntityModel payment) {
    var authentication = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(AuthenticationResult.class))
        .forwarder(forwarder())
        .processModel(payment)
        .contentParser(_ -> valid(new AuthenticationResult("authentication-reference", "provider", "cryptogram")))
        .onSuccess(PaymentEvent.AuthorisationRequested, TypedHttpResponse::payload)
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build(Authentication.name(), Authentication.id());
    return AtMostOnceDispatcher.of(authentication, _ -> request(Authentication));
  }

  /// The authorisation of payments by the acquirer, made with the payment model
  private static BatchedAtMostOnceWithRollbackDispatcher<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, AcquirerAuthorisationRequest, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> acquirerAuthorisationDispatcher(
      EntityModel payment
  ) {
    AtMostOnce<AcquirerAuthorisationRequest, Request> acquirerAuthorisation =
        HttpOutboxRequest.atMostOnce()
            .requestPayloadType(DataType.json(AcquirerAuthorisationRequest.class))
            .responsePayloadType(DataType.json(AcquirerResponse.class))
            .forwarder(forwarder())
            .processModel(payment)
            .contentParser(_ -> valid(AcquirerResponse.builder()
                .amount(1000L)
                .stan("1")
                .responseCode("00")
                .authorisationCode("123456")
                .batchNumber(2)
                .build()))
            .onSuccess(PaymentEvent.AcquirerResponded, TypedHttpResponse::payload)
            .onFailure(PaymentEvent.AcquirerDeclined, TypedHttpResponse::payload)
            .isAccepted(_ -> true)
            .isRejected(_ -> false)
            .isRejectedByInvalidResponse(_ -> false)
            .rollbackOnAcceptedModel(DummyTransitions.atLeastOnce(AuthorisationRollbackOnAccepted, payment))
            .rollbackOnUnknownModel(DummyTransitions.atLeastOnce(AuthorisationRollbackOnUnknown, payment))
            .build(
                PaymentTransitions.OutboxRequestModelIds.Authorisation.name(),
                PaymentTransitions.OutboxRequestModelIds.Authorisation.id()
            );
    return BatchedAtMostOnceWithRollbackDispatcher.of(
        acquirerAuthorisation,
        (Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID> d) -> new BatchedRequest<>(TypedHttpRequest.create(
            POST,
            URI.create("http://localhost:" + acquirer.getAddress().getPort() + "/" + PaymentTransitions.OutboxRequestModelIds.Authorisation.name()),
            new AcquirerAuthorisationRequest(
                d.t4().pan(),
                d.t4().psn(),
                d.t4().expiryDate(),
                d.t1().amount().currency(),
                d.t1().amount().requested(),
                d.t5().number(),
                d.t1().merchantReference(),
                d.t3().cryptogram()
            )
        ), d.t6()),
        (_, _) -> Mono.empty()
    );
  }

  private static NettyHttpClient forwarder() {
    return new NettyHttpClient(new NettyHttpClientBuilder().build());
  }

  private static TypedHttpRequest<Request> request(Identifiable id) {
    return TypedHttpRequest.create(
        POST,
        URI.create("http://localhost:" + acquirer.getAddress().getPort() + "/" + id.name()),
        new Request()
    );
  }

  private static <X> Dispatcher<X, TypedHttpRequest<Request>> dispatcher(Identifiable id) {
    return dispatcher(id, _ -> {});
  }

  private static <X> Dispatcher<X, TypedHttpRequest<Request>> dispatcher(Identifiable id, Consumer<X> dataListener) {
    return AtLeastOnceDispatcher.of(atLeastOnce(id), x -> {
      dataListener.accept(x);
      return request(id);
    });
  }

  private static AtLeastOnce<Request> atLeastOnce(Identifiable id) {
    return HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .forwarder(forwarder())
        .contentParser(_ -> valid(new Response()))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> false)
        .isFailureByInvalidResponseTransient(_ -> false)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build(id.name(), id.id());
  }

  private static HttpInboxRequest.Builder<byte[]> postRequest(String path) {
    return HttpInboxRequest.matching(m -> m.requestLine().matches("POST .*" + path)).contentParser(m -> valid(m.body()));
  }

}
