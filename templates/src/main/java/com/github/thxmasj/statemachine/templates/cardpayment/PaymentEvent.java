package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import java.time.ZonedDateTime;
import java.util.UUID;

public interface PaymentEvent {

  record Authorisation(
      String merchantId,
      Amount amount,
      String merchantReference,
      Boolean capture,
      Boolean inStore,
      ZonedDateTime transactionTime,
      PaymentToken paymentToken,
      String simulation
  ) {}

  record AuthenticationResult(
      String authenticationReference,
      String authenticationProviderId,
      String cryptogram
  ) {}

  record Amount(
      String currency,
      long requested,
      long cashback
  ) {}

  record Merchant(
      String aggregatorId,
      String id,
      String name,
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
      String correlationId,
      long amount,
      String simulation
  ) {}

  record Refund(
      String correlationId,
      long amount,
      boolean inStore,
      ZonedDateTime transactionTime,
      String simulation
  ) {}

  EventType<Tuple2<Authorisation, String>, Tuple2<Authorisation, Merchant>> PaymentRequest = BasicEventType.of("PaymentRequest", UUID.fromString("bf2eaeb5-cd26-4e5a-873e-f6d308387ec3"), new DataType<>(Authorisation.class, String.class), new DataType<>(Authorisation.class, Merchant.class));
  EventType<AuthenticationResult, Void> AuthenticationFailed = BasicEventType.of("AuthenticationFailed", UUID.fromString("ad1dc496-ecdd-4871-9a87-715df7b30aac"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, AuthenticationResult> Preauthorisation = BasicEventType.of("Preauthorisation", UUID.fromString("8327e33f-65bd-42f8-90da-8ba977c979a1"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> PreauthorisationApproved = BasicEventType.of("PreauthorisationApproved", UUID.fromString("ef315a8e-b9e7-4434-8710-4d238e6ac9c0"), AcquirerResponse.class);
  EventType<AuthenticationResult, Void> InvalidPaymentTokenOwnership = BasicEventType.of("InvalidPaymentTokenOwnership", UUID.fromString("778511db-70c0-443b-963a-4e614040256f"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, Void> InvalidPaymentTokenStatus = BasicEventType.of("InvalidPaymentTokenStatus", UUID.fromString("0e15a3a7-ee6d-4ddd-9eaa-5d98bf42d635"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, Void> InvalidAuthenticationToken = BasicEventType.of("InvalidAuthenticationToken", UUID.fromString("de24bd56-5ad1-4b1e-b567-8eb719c0ff51"), AuthenticationResult.class, Void.class);
  EventType<AuthenticationResult, AuthenticationResult> AuthorisationRequest = BasicEventType.of("AuthorisationRequest", UUID.fromString("4391c1cf-1cda-4627-9d9d-fe8b18b8b6cb"), AuthenticationResult.class);
  EventType<AuthenticationResult, AuthenticationResult> Authorisation = BasicEventType.of("Authorisation", UUID.fromString("7d36acf7-18b7-409f-bb8f-19a5f73d02c8"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationApproved = BasicEventType.of("AuthorisationApproved", UUID.fromString("4a3821a0-dbba-448b-8175-c40e4a771df4"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationAdviceApproved = BasicEventType.of("AuthorisationAdviceApproved", UUID.fromString("c1fd88f3-2821-4c4d-bee1-52750f10f554"), AcquirerResponse.class);
  EventType<Capture, Capture> CaptureRequest = BasicEventType.of("CaptureRequest", UUID.fromString("b4cef9f9-c9dd-40e4-a627-25ba529aec2e"), Capture.class);
  EventType<Capture, Capture> AcceptedCapture = BasicEventType.of("AcceptedCapture", UUID.fromString("0cce8545-ce69-4c4c-8e42-056df84297e7"), Capture.class);
  EventType<Capture, Capture> DeclinedCapture = BasicEventType.of("DeclinedCapture", UUID.fromString("d39b2369-f88a-482e-8715-49de39fbdf93"), Capture.class);
  EventType<AcquirerResponse, AcquirerResponse> CaptureApproved = BasicEventType.of("CaptureApproved", UUID.fromString("6186a241-f9e0-40ae-b444-6ce5e2509dcc"), AcquirerResponse.class);
  EventType<Refund, Refund> RefundRequest = BasicEventType.of("RefundRequest", UUID.fromString("4f6d6f15-f8a4-477e-b750-dc52a1f245eb"), Refund.class);
  EventType<Refund, Refund> AcceptedRefund = BasicEventType.of("AcceptedRefund", UUID.fromString("94513e5a-1ded-4379-9087-16a148903bb5"), Refund.class);
  EventType<Refund, Refund> DeclinedRefund = BasicEventType.of("DeclinedRefund", UUID.fromString("4bcd52bf-8abd-4973-8926-6391570ae29e"), Refund.class);
  EventType<AcquirerResponse, AcquirerResponse> RefundApproved = BasicEventType.of("RefundApproved", UUID.fromString("9c7f8d63-9b27-4bf3-8a90-b7bea56a3fbd"), AcquirerResponse.class);
  EventType<Void, Void> AuthorisationExpired = BasicEventType.of("AuthorisationExpired", UUID.fromString("8c283db8-742e-4667-a370-45bb0fb3f39e"));
  EventType<AcquirerResponse, Void> AcquirerDeclined = BasicEventType.of("AcquirerDeclined", UUID.fromString("994388bc-73a4-4334-ba16-41d471ee56b1"), AcquirerResponse.class, Void.class);
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> RollbackRequest = new BasicEventType.Rollback("RollbackRequest", UUID.fromString("da2d5fd5-beb7-4497-87f7-479ba7eb2a66"));
  EventType<BasicEventType.Rollback.Data, BasicEventType.Rollback.Data> Cancel = new BasicEventType.Cancel("Cancel", UUID.fromString("d7bb2f14-4680-476e-8395-c972a1037589"));

}
