package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.TRIGGER;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.statemachine.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.Index;
import static com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method.POST;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedPreauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Capture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.CaptureRequestedTooLate;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Preauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Refund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackPreauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.ApprovedCutOff;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.Reconciliation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventTrigger;
import com.github.thxmasj.statemachine.Identifiable;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.StateMachine;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.Tuples.Tuple6;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.statemachine.http.NettyHttpClient;
import com.github.thxmasj.statemachine.http.NettyHttpClientBuilder;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.statemachine.http.inbox.HttpRequestRoute;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnceDispatcher;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnceDispatcher;
import com.github.thxmasj.statemachine.http.outbox.BatchedAtMostOnceWithRollbackDispatcher;
import com.github.thxmasj.statemachine.http.outbox.Dispatcher;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.BatchedRequest;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.TypedHttpRequest;
import com.github.thxmasj.statemachine.message.http.TypedHttpResponse;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyTransitions.Request;
import com.github.thxmasj.statemachine.templates.cardpayment.DummyTransitions.Response;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerAuthorisationRequest;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant.Location;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.ReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
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
/// being sent to the acquirer.
public class CutOffReconciliationTest {

  private static final String AGGREGATOR_ID = "aggregator";
  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig(
      "testlogin",
      "Please_hide_me!"
  )).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig(
      "sa",
      "A_Str0ng_Required_Password"
  )).build();

  private static HttpServer acquirer;
  // The paths of the requests received by the acquirer and the merchant (they share the same test server)
  private static final ConcurrentLinkedQueue<String> receivedPaths = new ConcurrentLinkedQueue<>();
  // The input of the reconciliation request. It is dispatched by a condition trigger, so the input has been stored in
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

    // The input data of the condition triggered reconciliation request survived the database round trip
    assertEquals(
        tuple(new BatchNumber(merchantId, 1), new AcquirerBatchNumber(merchantId, 2), merchant(merchantId)),
        reconciliationInput.get()
    );
  }

  private static String authorisationPath() {
    return PaymentTransitions.OutboxRequestModelIds.Authorisation.name();
  }

  private static void await(String description, java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(15).toMillis();
    while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) Thread.sleep(200);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for " + description + ". Received: " + receivedPaths);
  }

  private static Event<?> request(StateMachine machine, String path) {
    var message = new HttpRequestMessage(POST, URI.create(path), Map.of(), "{}".getBytes());
    return machine.onEvent(UUID.randomUUID().toString(), (EventTrigger<HttpRequestMessage, ?, ?>) TRIGGER, message)
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

  private static StateMachine stateMachine(String merchantId) {
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

    List<HttpRequestRoute<?>> routes = List.of(
        new HttpRequestRoute<>(
            m -> m.requestLine().matches("POST .*/merchants"),
            m -> valid(m.body()),
            List.of(anyContent(
                null,
                null,
                UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0001"),
                MerchantEvent.Create,
                Aggregate.Merchant,
                (RoutedRequest<byte[]> _) -> merchant,
                newEntity()
            ))
        ),
        new HttpRequestRoute<>(
            m -> m.requestLine().matches("POST .*/payments"),
            m -> valid(m.body()),
            List.of(anyContent(
                null,
                null,
                UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0002"),
                PaymentEvent.PaymentRequest,
                Aggregate.Payment,
                (RoutedRequest<byte[]> _) -> tuple(authorisation, new byte[0]),
                newEntity()
            ))
        ),
        new HttpRequestRoute<>(
            m -> m.requestLine().matches("POST .*/cutoffs"),
            m -> valid(m.body()),
            List.of(anyContent(
                null,
                null,
                UUID.fromString("c3b0b8a0-6b0f-4a35-9d57-0a5f0f0e0003"),
                SettlementEvent.CutOffRequest,
                Aggregate.Settlement,
                (RoutedRequest<byte[]> _) -> cutOff,
                (_, _) -> new Valid<>(secondaryId(BatchNumber, new BatchNumber(merchantId, 1)))
            ))
        )
    );

    var authentication = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(AuthenticationResult.class))
        .forwarder(forwarder())
        .processModel(Aggregate.Payment)
        .contentParser(_ -> valid(new AuthenticationResult("authentication-reference", "provider", "cryptogram")))
        .onSuccess(PaymentEvent.Authorisation, TypedHttpResponse::payload)
        .isAccepted(_ -> true)
        .isRejected(_ -> false)
        .isRejectedByInvalidResponse(_ -> false)
        .build(Authentication.name(), Authentication.id());
    Dispatcher<AuthenticationData, TypedHttpRequest<Request>> authenticationDispatcher = AtMostOnceDispatcher.of(authentication, _ -> request(Authentication));

    AtMostOnce<AcquirerAuthorisationRequest, Request> acquirerAuthorisation =
        HttpOutboxRequest.atMostOnce()
            .requestPayloadType(DataType.json(AcquirerAuthorisationRequest.class))
            .responsePayloadType(DataType.json(AcquirerResponse.class))
            .forwarder(forwarder())
            .processModel(Aggregate.Payment)
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
            .rollbackOnAcceptedModel(
                DummyTransitions.atLeastOnce(AuthorisationRollbackOnAccepted)
            )
            .rollbackOnUnknownModel(DummyTransitions.atLeastOnce(AuthorisationRollbackOnUnknown))
            .build(
                PaymentTransitions.OutboxRequestModelIds.Authorisation.name(),
                PaymentTransitions.OutboxRequestModelIds.Authorisation.id()
            );
    BatchedAtMostOnceWithRollbackDispatcher<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, AcquirerAuthorisationRequest, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> acquirerAuthorisationDispatcher =
        BatchedAtMostOnceWithRollbackDispatcher.of(
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
    Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, TypedHttpRequest<Request>> reconciliation = dispatcher(
        Reconciliation,
        reconciliationInput::set
    );

    var payment = new PaymentTransitions(
        _ -> new PaymentToken("4111111111111111", "00", "3012"),
        authenticationDispatcher,
        DummyTransitions.reversibleDispatcher(Preauthorisation, PreauthorisationRollbackOnAccepted, PreauthorisationRollbackOnUnknown),
        acquirerAuthorisationDispatcher,
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(Capture)),
        DummyTransitions.reversibleDispatcher(Refund, RefundRollbackOnAccepted, RefundRollbackOnUnknown),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(CaptureRequestedTooLate)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedAuthentication)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(RolledBackAuthorisation)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(RolledBackPreauthorisation)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedPreauthorisation)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedAuthorisation)),
        dispatcher(ApprovedAuthorisation),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(DeclinedAuthorisation)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedCapture)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(FailedRefund)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedRefund)),
        DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(DeclinedRefund))
    );
    var settlement = new SettlementTransitions(reconciliation, DummyTransitions.dispatcher(DummyTransitions.atLeastOnce(ApprovedCutOff)));

    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(Aggregate.Merchant, new MerchantTransitions().transitions());
    transitions.put(Aggregate.Payment, payment.transitions());
    transitions.put(Aggregate.Settlement, settlement.transitions());
    transitions.put(RequestRouting, requestRoutingTransitions(routes));
    transitions.put(RequestDispatching, requestDispatchingTransitions(routes, List.of()));
    transitions.put(Index, Index.transitions());
    transitions.putAll(payment.outboxTransitions());
    transitions.putAll(settlement.outboxTransitions());

    return new StateMachine(
        _ -> Mono.empty(),
        transitions,
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(),
        "Test",
        Clock.systemUTC(),
        new Logger("CutOffReconciliationTest")
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

  private static Config databaseConfig(String username, String password) {
    return new Config("localhost", 11433, "work", username, password, true, 10, Duration.ofSeconds(10));
  }

}
