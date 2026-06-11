package com.github.thxmasj.statemachine.templates.cardpayment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.RequestEventType;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant.Location;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime;
import java.time.ZonedDateTime;
import java.util.UUID;

public interface PaymentEvent {

  record Authorisation(
      String merchantId,
      MerchantDetails merchantDetails,
      String clientId,
      Amount amount,
      String merchantReference,
      Boolean capture,
      Boolean inStore,
      ZonedDateTime transactionTime,
      String authenticationData,
      String simulation
  ) {
    public record MerchantDetails(
        String displayName,
        String categoryCode,
        Location location
    ) {}
  }

  record AuthenticationResult(
      String authenticationReference,
      String authenticationProviderId,
      String cryptogram
  ) {}

  record Amount(
      String currency,
      long requested,
      Breakdown breakdown
  ) {
    public record Breakdown(long purchase, long cashback) {}
  }

  record Merchant(
      String aggregatorId,
      String id,
      String displayName,
      Location location,
      String categoryCode,
      String acquirerId,
      boolean superMerchant
  ) {

    public record Location(
        String address,
        String zipCode,
        String city
    ) {}
  }

  record PaymentToken(
      String pan,
      String psn,
      String expiryDate
  ) {}

  record Capture(
      long amount,
      String simulation
  ) {}

  record Refund(
      long amount,
      boolean inStore,
      ZonedDateTime transactionTime,
      String simulation
  ) {}

  EventType<Tuple2<Authorisation, String>, Void> PaymentRequest = BasicEventType.of("Payment request", UUID.fromString("bf2eaeb5-cd26-4e5a-873e-f6d308387ec3"), new DataType<>(new TypeReference<>() {}, Authorisation.class, String.class), Void.class);
  EventType<Tuple3<Authorisation, Merchant, AuthenticationData>, Tuple2<Authorisation, Merchant>> ValidPaymentRequest = RequestEventType.of("Valid payment request", UUID.fromString("a67a80c1-9b16-4445-9a14-76f114f49827"), new DataType<>(new TypeReference<>() {}, Authorisation.class, Merchant.class, AuthenticationData.class), new DataType<>(new TypeReference<>() {}, Authorisation.class, Merchant.class));
  EventType<MerchantId, MerchantId> UnknownMerchant = BasicEventType.of("Unknown merchant", UUID.fromString("85638b45-2bc6-4363-901f-7c35d8a642b6"), MerchantId.class);
  EventType<MerchantId, MerchantId> IllegalMerchant = BasicEventType.of("Illegal merchant", UUID.fromString("04ae10a7-fa0f-4f2e-bdbc-22371ef29c39"), MerchantId.class);
  EventType<Void, Void> InsufficientMerchantDetails = BasicEventType.of("Insufficient merchant details", UUID.fromString("a7f228ba-f5d2-43ab-bfc1-531954d223e7"), Void.class);
  EventType<ValidatedAmount.Invalid, ValidatedAmount.Invalid> InvalidAmount = BasicEventType.of("Invalid amount", UUID.fromString("c19e6784-4059-4216-b67b-087ca5f2e764"), ValidatedAmount.Invalid.class);
  EventType<ValidatedTransactionTime.Invalid, ValidatedTransactionTime.Invalid> InvalidTransactionTime = BasicEventType.of("Invalid transaction time", UUID.fromString("9db62961-6fc5-48e1-95fe-0157d62f309c"), ValidatedTransactionTime.Invalid.class);
  EventType<AuthenticationResult, Void> AuthenticationFailed = BasicEventType.of("Authentication failed", UUID.fromString("ad1dc496-ecdd-4871-9a87-715df7b30aac"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, AuthenticationResult> Preauthorisation = BasicEventType.of("Pre-authorization", UUID.fromString("8327e33f-65bd-42f8-90da-8ba977c979a1"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> PreauthorisationApproved = BasicEventType.of("Pre-authorization approved", UUID.fromString("ef315a8e-b9e7-4434-8710-4d238e6ac9c0"), AcquirerResponse.class);
  EventType<AuthenticationResult, Void> InvalidPaymentTokenOwnership = BasicEventType.of("Invalid payment token ownership", UUID.fromString("778511db-70c0-443b-963a-4e614040256f"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, Void> InvalidPaymentTokenStatus = BasicEventType.of("Invalid payment token status", UUID.fromString("0e15a3a7-ee6d-4ddd-9eaa-5d98bf42d635"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, Void> InvalidAuthenticationToken = BasicEventType.of("Invalid authentication token", UUID.fromString("de24bd56-5ad1-4b1e-b567-8eb719c0ff51"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, AuthenticationResult> Authorisation = BasicEventType.of("Authorization", UUID.fromString("7d36acf7-18b7-409f-bb8f-19a5f73d02c8"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationApproved = BasicEventType.of("Authorization approved", UUID.fromString("4a3821a0-dbba-448b-8175-c40e4a771df4"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationAdviceApproved = BasicEventType.of("Authorization advice approved", UUID.fromString("c1fd88f3-2821-4c4d-bee1-52750f10f554"), AcquirerResponse.class);
  EventType<Capture, Capture> CaptureRequest = BasicEventType.of("Capture request", UUID.fromString("b4cef9f9-c9dd-40e4-a627-25ba529aec2e"), Capture.class);
  EventType<CaptureRequestData, Capture> ValidCaptureRequest = RequestEventType.of("Valid capture request", UUID.fromString("0cce8545-ce69-4c4c-8e42-056df84297e7"), CaptureRequestData.class, Capture.class);
  EventType<Capture, Capture> DeclinedUnauthorisedCapture = BasicEventType.of("Decline unauthorized capture", UUID.fromString("d39b2369-f88a-482e-8715-49de39fbdf93"), Capture.class);
  EventType<Capture, Capture> DeclineLateCapture = BasicEventType.of("Declined late capture", UUID.fromString("5da4f734-9779-41b9-a653-58388a63b8b9"), Capture.class);
  EventType<AcquirerResponse, AcquirerResponse> CaptureApproved = BasicEventType.of("Capture approved", UUID.fromString("6186a241-f9e0-40ae-b444-6ce5e2509dcc"), AcquirerResponse.class);
  EventType<Refund, Refund> RefundRequest = BasicEventType.of("Refund request", UUID.fromString("4f6d6f15-f8a4-477e-b750-dc52a1f245eb"), Refund.class);
  EventType<Refund, Refund> ValidRefundRequest = RequestEventType.of("Valid refund request", UUID.fromString("94513e5a-1ded-4379-9087-16a148903bb5"), Refund.class);
  EventType<Refund, Refund> DeclinedRefund = BasicEventType.of("Declined refund", UUID.fromString("4bcd52bf-8abd-4973-8926-6391570ae29e"), Refund.class);
  EventType<AcquirerResponse, AcquirerResponse> RefundApproved = BasicEventType.of("Refund approved", UUID.fromString("9c7f8d63-9b27-4bf3-8a90-b7bea56a3fbd"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> AcquirerDeclined = BasicEventType.of("Acquirer declined", UUID.fromString("994388bc-73a4-4334-ba16-41d471ee56b1"), AcquirerResponse.class, AcquirerResponse.class);
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> RollbackRequest = new BasicEventType.Rollback("RollbackRequest", UUID.fromString("da2d5fd5-beb7-4497-87f7-479ba7eb2a66"));
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> Cancel = new BasicEventType.Cancel("Cancel", UUID.fromString("d7bb2f14-4680-476e-8395-c972a1037589"));

}
