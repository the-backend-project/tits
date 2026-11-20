package com.github.thxmasj.backend.docdiagrams;

import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.entityId;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.parseMessageId;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.when;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.http.HttpClient;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.TypedHttpResponse;
import com.github.thxmasj.backend.http.inbox.Authorization;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest.Rollback;
import com.github.thxmasj.backend.http.outbox.AtLeastOnce;
import com.github.thxmasj.backend.http.outbox.AtMostOnce;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.http.outbox.JsonResponseValidator;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/// The models of the code examples in the documentation, written as the examples show them, so that the diagram under
/// an example is drawn from the model the example creates. What the examples take from their surroundings, such as an
/// HTTP client or an authorizer verifying bearer tokens, is given here by something which draws the same diagram.
final class DocExamples {

  private DocExamples() {}

  // Stands in for HttpInbox.bearerToken(verifier, "orders"), as a verifier needs the keys of an issuer. The diagram is
  // the same for any authorizer.
  private static <T> BiFunction<HttpRequestMessage, T, Authorization> bearerToken() {
    return (_, _) -> Authorization.authorized("client");
  }

  private static final HttpClient httpClient = null;

  // docs/http-inbox.md

  record Order(String item) {}

  static final EntityModel Orders = EntityModel.of("Orders", UUID.fromString("6f0a6c55-2a3e-4a0b-9d3c-1c4f6d2b7e10"), Map::of);

  static final EventType<Order, Void> OrderPlaced = BasicEventType.of(
      "Order placed",
      UUID.fromString("0d9b3f4e-8c1a-4e57-b2f6-3a7d5c9e1b24"),
      DataType.json(Order.class),
      DataType.none()
  );

  static final HttpInboxRequest<Order> PlaceOrder = HttpInboxRequest
      .matching(m -> m.method() == Method.PUT && m.uri().getPath().startsWith("/orders/"))
      .contentParser(HttpInbox.jsonParser(Order.class))
      .deduplicateOn(parseMessageId("PUT /orders/(.*)", 1))
      .authorizer(bearerToken())
      .process(OrderPlaced, Orders, RoutedRequest::body, newEntity())
      .build("PlaceOrder", UUID.fromString("2b7c9e14-5d3f-4a68-8e0b-7f1a6c3d9e52"));

  static final HttpInboxRequest<Order> PlaceOrderWithRollback = HttpInboxRequest
      .matching(m -> m.method() == Method.PUT && m.uri().getPath().startsWith("/orders/"))
      .contentParser(HttpInbox.jsonParser(Order.class))
      .deduplicateOn(parseMessageId("PUT /orders/(.*)", 1))
      .authorizer(bearerToken())
      .process(OrderPlaced, Orders, RoutedRequest::body, newEntity())
      .rollback(Rollback
          .matching(m -> m.method() == Method.DELETE && m.uri().getPath().startsWith("/orders/"))
          .noContent()
          .deduplicateOn(parseMessageId("DELETE /orders/(.*)", 1))
          .authorizer(bearerToken()))
      .build("PlaceOrder", UUID.fromString("2b7c9e14-5d3f-4a68-8e0b-7f1a6c3d9e52"));

  enum EnrolmentStatus {ACCEPTED, REJECTED}

  record EnrolmentResult(String messageId, EnrolmentStatus status, UUID tokenReference) {}

  static final EntityModel PaymentToken = EntityModel.of("PaymentToken", UUID.fromString("9a2e5c71-3b8d-4f04-a6e9-2d7c1b5f8e33"), Map::of);

  static final EventType<EnrolmentResult, Void> Activate = BasicEventType.of(
      "Activate",
      UUID.fromString("4c8e1a9f-6b2d-4e73-9f05-8a3c7d1e6b49"),
      DataType.json(EnrolmentResult.class),
      DataType.none()
  );

  static final EventType<Void, Void> Reject = BasicEventType.of("Reject", UUID.fromString("e7b3d2a6-9c4f-4a18-b5e0-6d1f8c2a7e95"));

  static final HttpInboxRequest<EnrolmentResult> EnrolmentResultReceived = HttpInboxRequest
      .matching(m -> m.requestLine().matches("POST /enrolments"))
      .contentParser(HttpInbox.jsonParser(EnrolmentResult.class))
      .deduplicateOn((_, result) -> valid(result.messageId()))
      .process(
          when("status == Accepted", result -> result.status() == EnrolmentStatus.ACCEPTED),
          Activate, PaymentToken, RoutedRequest::body, (_, result) -> entityId(result.tokenReference())
      )
      .process(
          when("status == Rejected", result -> result.status() == EnrolmentStatus.REJECTED),
          Reject, PaymentToken, (_, result) -> entityId(result.tokenReference())
      )
      .build("EnrolmentResultReceived", UUID.fromString("5f1c8b3e-7a2d-4c96-8e4b-1d9a6f3c2e70"));

  // docs/http-outbox.md

  record Notification(String text) {}

  record Receipt(String id) {}

  static final AtLeastOnce<Notification> MerchantNotification = HttpOutboxRequest.atLeastOnce()
      .requestPayloadType(DataType.json(Notification.class))
      .responsePayloadType(DataType.json(Receipt.class))
      .forwarder(httpClient)
      .inflightTimeout(Duration.ofSeconds(5))
      .contentParser(JsonResponseValidator.json(Receipt.class))
      .isAccepted(_ -> true)
      .isFailureTransient(response -> response.statusCode() >= 500)
      .isRejectedByInvalidResponse(d -> d.t1().statusCode() >= 400)
      .isFailureByInvalidResponseTransient(d -> d.t1().statusCode() >= 500)
      .isAttemptAvailable(retry -> retry.now().isBefore(retry.enqueueTime().plusDays(1)))
      .backoffAlgorithm(_ -> Duration.ofSeconds(30))
      .build("MerchantNotification", UUID.fromString("8b4f2e6a-1c7d-4a39-9e5b-3f6a8d2c1e07"));

  record AuthorisationRequest(String payment) {}

  record AcquirerResponse(boolean approved) {}

  record ReversalRequest(String authorisation) {}

  static final EntityModel Payment = EntityModel.of("Payment", UUID.fromString("c3a7e1f9-4b2d-4d68-a0e5-9b1f7c3d6a28"), Map::of);

  static final EventType<Void, Void> AuthorisationUnknown = BasicEventType.of(
      "Authorisation unknown",
      UUID.fromString("1e6d9b3a-7f2c-4a85-b4e1-8c5a2f7d3b96")
  );

  static final EventType<AcquirerResponse, Void> AcquirerResponded = BasicEventType.of(
      "Acquirer responded",
      UUID.fromString("a5f2c8e1-3d9b-4e47-8a6c-2b7e1d4f9c53"),
      DataType.json(AcquirerResponse.class),
      DataType.none()
  );

  static final EventType<AcquirerResponse, Void> AcquirerDeclined = BasicEventType.of(
      "Acquirer declined",
      UUID.fromString("7d3b1f8a-5e2c-4c90-9b6d-4a1e8f2c7d65"),
      DataType.json(AcquirerResponse.class),
      DataType.none()
  );

  static final AtLeastOnce<ReversalRequest> Reversal = HttpOutboxRequest.atLeastOnce()
      .requestPayloadType(DataType.json(ReversalRequest.class))
      .responsePayloadType(DataType.json(AcquirerResponse.class))
      .forwarder(httpClient)
      .inflightTimeout(Duration.ofSeconds(5))
      .contentParser(JsonResponseValidator.json(AcquirerResponse.class))
      .isAccepted(_ -> true)
      .isFailureTransient(response -> response.statusCode() >= 500)
      .isRejectedByInvalidResponse(d -> d.t1().statusCode() >= 400)
      .isFailureByInvalidResponseTransient(d -> d.t1().statusCode() >= 500)
      .isAttemptAvailable(retry -> retry.now().isBefore(retry.enqueueTime().plusDays(1)))
      .backoffAlgorithm(_ -> Duration.ofSeconds(30))
      .build("Reversal", UUID.fromString("f9c4a2e7-6b1d-4f53-8e9a-5c2d7b3f1a84"));

  static final AtMostOnce<AuthorisationRequest, ReversalRequest> Authorisation = HttpOutboxRequest.atMostOnce()
      .requestPayloadType(DataType.json(AuthorisationRequest.class))
      .responsePayloadType(DataType.json(AcquirerResponse.class))
      .forwarder(httpClient)
      .processModel(Payment)
      .onMissingResponse(AuthorisationUnknown)
      .contentParser(JsonResponseValidator.json(AcquirerResponse.class))
      .onSuccess(AcquirerResponded, TypedHttpResponse::payload)
      .onFailure(AcquirerDeclined, TypedHttpResponse::payload)
      .isAccepted(response -> response.payload().approved())
      .isRejected(response -> !response.payload().approved())
      .isRejectedByInvalidResponse(_ -> false)
      .rollbackOnAcceptedModel(Reversal)
      .rollbackOnUnknownModel(Reversal)
      .build("Authorisation", UUID.fromString("3d8a6f2c-9e1b-4a75-b3c8-7f4e2a9d1c66"));

}
