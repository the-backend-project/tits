package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.RequestEventType;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.backend.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant.Location;
import com.github.thxmasj.backend.templates.cardpayment.validators.ValidatedAmount;
import com.github.thxmasj.backend.templates.cardpayment.validators.ValidatedTransactionTime;
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

  record ReversalData(
      UUID paymentId,
      boolean clientOriginated,
      boolean technicalReversal,
      long amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

  record AuthenticationResult(
      String authenticationReference,
      String authenticationProviderId,
      String cryptogram
  ) {
  }

  record FailedAuthenticationResult(
      String authenticationReference,
      String authenticationProviderId,
      Status status
  ) {
    public enum Status {InvalidPaymentTokenOwnership, InvalidAuthenticationToken, InvalidAuthentication, InvalidPaymentTokenStatus}
  }

  record AcquirerAuthorisation(
    AuthenticationResult authenticationResult,
    UUID requestId
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

  record AcquirerRefund(
      Refund refund,
      UUID requestId
  ) {}

  EventType<Tuple2<Authorisation, byte[]>, Void> PaymentRequested = BasicEventType.of("Payment requested", UUID.fromString("bf2eaeb5-cd26-4e5a-873e-f6d308387ec3"), DataType.tuple(DataType.json(Authorisation.class), DataType.binary()), DataType.none());
  EventType<Tuple3<Authorisation, Merchant, AuthenticationData>, Tuple2<Authorisation, Merchant>> PaymentRequestValidated = RequestEventType.of(
      "Payment request validated",
      UUID.fromString("a67a80c1-9b16-4445-9a14-76f114f49827"),
      DataType.tuple(
          DataType.json(Authorisation.class),
          DataType.json(Merchant.class),
          DataType.json(AuthenticationData.class)
      ),
      DataType.tuple(DataType.json(Authorisation.class), DataType.json(Merchant.class))
  );
  EventType<MerchantId, MerchantId> UnknownMerchantDetected = BasicEventType.of("Unknown merchant detected", UUID.fromString("85638b45-2bc6-4363-901f-7c35d8a642b6"), DataType.json(MerchantId.class));
  EventType<MerchantId, MerchantId> InactiveMerchantDetected = BasicEventType.of("Inactive merchant detected", UUID.fromString("ece8696c-3e7d-412d-bc6c-b8ff1d925b0c"), DataType.json(MerchantId.class));
  EventType<MerchantId, MerchantId> IllegalMerchantDetected = BasicEventType.of("Illegal merchant detected", UUID.fromString("04ae10a7-fa0f-4f2e-bdbc-22371ef29c39"), DataType.json(MerchantId.class));
  EventType<Void, Void> InsufficientMerchantDetailsDetected = BasicEventType.of("Insufficient merchant details detected", UUID.fromString("a7f228ba-f5d2-43ab-bfc1-531954d223e7"), DataType.none());
  EventType<ValidatedAmount.Invalid, ValidatedAmount.Invalid> InvalidAmountDetected = BasicEventType.of("Invalid amount detected", UUID.fromString("c19e6784-4059-4216-b67b-087ca5f2e764"), DataType.json(ValidatedAmount.Invalid.class));
  EventType<ValidatedTransactionTime.Invalid, ValidatedTransactionTime.Invalid> InvalidTransactionTimeDetected = BasicEventType.of("Invalid transaction time detected", UUID.fromString("9db62961-6fc5-48e1-95fe-0157d62f309c"), DataType.json(ValidatedTransactionTime.Invalid.class));
  EventType<Void, Void> AuthenticationUnavailabilityDetected = BasicEventType.of("Authentication unavailability detected", UUID.fromString("5c0450f5-6254-4b8e-91c8-bca833486d16"));
  EventType<FailedAuthenticationResult, Void> AuthenticationFailed = BasicEventType.of("Authentication failed", UUID.fromString("601f3431-4f47-41e7-ba06-6d0cbbcd9a87"), DataType.json(FailedAuthenticationResult.class), DataType.none());
  EventType<FailedAuthenticationResult, Void> InvalidAuthenticationDetected = BasicEventType.of("Invalid authentication detected", UUID.fromString("ad1dc496-ecdd-4871-9a87-715df7b30aac"), DataType.json(FailedAuthenticationResult.class), DataType.none());
  EventType<AuthenticationResult, AcquirerAuthorisation> PreauthorisationRequested = BasicEventType.of("Pre-authorization requested", UUID.fromString("8327e33f-65bd-42f8-90da-8ba977c979a1"), DataType.json(AuthenticationResult.class), DataType.json(AcquirerAuthorisation.class));
  EventType<AcquirerResponse, AcquirerResponse> PreauthorisationApproved = BasicEventType.of("Pre-authorization approved", UUID.fromString("ef315a8e-b9e7-4434-8710-4d238e6ac9c0"), DataType.json(AcquirerResponse.class));
  EventType<FailedAuthenticationResult, Void> InvalidPaymentTokenOwnershipDetected = BasicEventType.of("Invalid payment token ownership detected", UUID.fromString("778511db-70c0-443b-963a-4e614040256f"), DataType.json(FailedAuthenticationResult.class), DataType.none());
  EventType<AuthenticationResult, Void> InvalidPaymentTokenStatusDetected = BasicEventType.of("Invalid payment token status detected", UUID.fromString("0e15a3a7-ee6d-4ddd-9eaa-5d98bf42d635"), DataType.json(AuthenticationResult.class), DataType.none());
  EventType<Void, Void> InvalidAuthenticationTokenDetected = BasicEventType.of("Invalid authentication token detected", UUID.fromString("de24bd56-5ad1-4b1e-b567-8eb719c0ff51"));
  EventType<AuthenticationResult, AcquirerAuthorisation> AuthorisationRequested = BasicEventType.of("Authorization requested", UUID.fromString("7d36acf7-18b7-409f-bb8f-19a5f73d02c8"), DataType.json(AuthenticationResult.class), DataType.json(AcquirerAuthorisation.class));
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationApproved = BasicEventType.of("Authorization approved", UUID.fromString("4a3821a0-dbba-448b-8175-c40e4a771df4"), DataType.json(AcquirerResponse.class));
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationAdviceApproved = BasicEventType.of("Authorization advice approved", UUID.fromString("c1fd88f3-2821-4c4d-bee1-52750f10f554"), DataType.json(AcquirerResponse.class));
  EventType<Capture, Capture> CaptureRequested = BasicEventType.of("Capture requested", UUID.fromString("b4cef9f9-c9dd-40e4-a627-25ba529aec2e"), DataType.json(Capture.class));
  EventType<CaptureRequestData, Capture> CaptureRequestValidated = RequestEventType.of("Capture request validated", UUID.fromString("0cce8545-ce69-4c4c-8e42-056df84297e7"), DataType.json(CaptureRequestData.class), DataType.json(Capture.class));
  EventType<Capture, Capture> UnauthorisedCaptureDeclined = BasicEventType.of("Unauthorized capture declined", UUID.fromString("d39b2369-f88a-482e-8715-49de39fbdf93"), DataType.json(Capture.class));
  EventType<Capture, Capture> LateCaptureDeclined = BasicEventType.of("Late capture declined", UUID.fromString("5da4f734-9779-41b9-a653-58388a63b8b9"), DataType.json(Capture.class));
  EventType<AcquirerResponse, AcquirerResponse> CaptureApproved = BasicEventType.of("Capture approved", UUID.fromString("6186a241-f9e0-40ae-b444-6ce5e2509dcc"), DataType.json(AcquirerResponse.class));
  EventType<Refund, Refund> RefundRequested = BasicEventType.of("Refund requested", UUID.fromString("4f6d6f15-f8a4-477e-b750-dc52a1f245eb"), DataType.json(Refund.class));
  EventType<Refund, AcquirerRefund> RefundRequestValidated = RequestEventType.of("Refund request validated", UUID.fromString("94513e5a-1ded-4379-9087-16a148903bb5"), DataType.json(Refund.class), DataType.json(AcquirerRefund.class));
  EventType<Refund, Refund> RefundDeclined = BasicEventType.of("Refund declined", UUID.fromString("4bcd52bf-8abd-4973-8926-6391570ae29e"), DataType.json(Refund.class));
  EventType<AcquirerResponse, AcquirerResponse> RefundApproved = BasicEventType.of("Refund approved", UUID.fromString("9c7f8d63-9b27-4bf3-8a90-b7bea56a3fbd"), DataType.json(AcquirerResponse.class));
  EventType<AcquirerResponse, AcquirerResponse> AcquirerResponded = BasicEventType.of("Acquirer responded", UUID.fromString("267efd41-3ae3-4ad8-aa80-a7d20137e5b1"), DataType.json(AcquirerResponse.class), DataType.json(AcquirerResponse.class));
  EventType<AcquirerResponse, AcquirerResponse> AcquirerDeclined = BasicEventType.of("Acquirer declined", UUID.fromString("994388bc-73a4-4334-ba16-41d471ee56b1"), DataType.json(AcquirerResponse.class), DataType.json(AcquirerResponse.class));
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> RollbackRequested = new BasicEventType.Rollback("Rollback requested", UUID.fromString("da2d5fd5-beb7-4497-87f7-479ba7eb2a66"));
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> PaymentCancelled = new BasicEventType.Cancel("Payment cancelled", UUID.fromString("d7bb2f14-4680-476e-8395-c972a1037589"));

}
