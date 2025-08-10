package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;
import java.time.ZonedDateTime;
import java.util.UUID;

public interface PaymentEvent {

  record Authorisation(
      Merchant merchant,
      Amount amount,
      String merchantReference,
      Boolean capture,
      Boolean inStore,
      ZonedDateTime transactionTime,
      PaymentToken paymentToken,
      String requestBody,
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

  EventType<Authorisation, Authorisation> PaymentRequest = EventType.of("PaymentRequest", UUID.fromString("bf2eaeb5-cd26-4e5a-873e-f6d308387ec3"), Authorisation.class);
  EventType<AuthenticationResult, AuthenticationResult> AuthenticationFailed = EventType.of("AuthenticationFailed", UUID.fromString("ad1dc496-ecdd-4871-9a87-715df7b30aac"), AuthenticationResult.class);
  EventType<AuthenticationResult, AuthenticationResult> PreauthorisationRequest = EventType.of("PreauthorisationRequest", UUID.fromString("ead7e832-3d32-407b-8316-b23023e8217c"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> PreauthorisationApproved = EventType.of("PreauthorisationApproved", UUID.fromString("ef315a8e-b9e7-4434-8710-4d238e6ac9c0"), AcquirerResponse.class);
  EventType<AuthenticationResult, AuthenticationResult> InvalidPaymentTokenOwnership = EventType.of("InvalidPaymentTokenOwnership", UUID.fromString("778511db-70c0-443b-963a-4e614040256f"), AuthenticationResult.class);
  EventType<AuthenticationResult, AuthenticationResult> InvalidPaymentTokenStatus = EventType.of("InvalidPaymentTokenStatus", UUID.fromString("0e15a3a7-ee6d-4ddd-9eaa-5d98bf42d635"), AuthenticationResult.class);
  EventType<AuthenticationResult, AuthenticationResult> InvalidAuthenticationToken = EventType.of("InvalidAuthenticationToken", UUID.fromString("de24bd56-5ad1-4b1e-b567-8eb719c0ff51"), AuthenticationResult.class);
  EventType<AuthenticationResult, AuthenticationResult> AuthorisationRequest = EventType.of("AuthorisationRequest", UUID.fromString("4391c1cf-1cda-4627-9d9d-fe8b18b8b6cb"), AuthenticationResult.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationAdviceApproved = EventType.of("AuthorisationAdviceApproved", UUID.fromString("fc91e51a-0b0c-4014-9587-fe6d03af3c46"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> AuthorisationApproved = EventType.of("AuthorisationApproved", UUID.fromString("4a3821a0-dbba-448b-8175-c40e4a771df4"), AcquirerResponse.class);
  EventType<Capture, Capture> CaptureRequest = EventType.of("CaptureRequest", UUID.fromString("b4cef9f9-c9dd-40e4-a627-25ba529aec2e"), Capture.class);
  EventType<AcquirerResponse, AcquirerResponse> CaptureApproved = EventType.of("CaptureApproved", UUID.fromString("6186a241-f9e0-40ae-b444-6ce5e2509dcc"), AcquirerResponse.class);
  EventType<Refund, Refund> RefundRequest = EventType.of("RefundRequest", UUID.fromString("4f6d6f15-f8a4-477e-b750-dc52a1f245eb"), Refund.class);
  EventType<AcquirerResponse, AcquirerResponse> RefundApproved = EventType.of("RefundApproved", UUID.fromString("9c7f8d63-9b27-4bf3-8a90-b7bea56a3fbd"), AcquirerResponse.class);
  EventType<Void, Void> AuthorisationExpired = EventType.of("AuthorisationExpired", UUID.fromString("8c283db8-742e-4667-a370-45bb0fb3f39e"));
  EventType<AcquirerResponse, AcquirerResponse> AcquirerDeclined = EventType.of("AcquirerDeclined", UUID.fromString("994388bc-73a4-4334-ba16-41d471ee56b1"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> BankRespondedIncomprehensibly = EventType.of("BankRespondedIncomprehensibly", UUID.fromString("3350b7b6-4a31-45e2-9585-7522a4697497"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> BankRequestFailed = EventType.of("BankRequestFailed", UUID.fromString("3901db73-9647-417b-8a6c-1f81250a03e0"), AcquirerResponse.class);
  EventType<Void, Void> RollbackRequest = EventType.rollback("RollbackRequest", UUID.fromString("da2d5fd5-beb7-4497-87f7-479ba7eb2a66"));
  EventType<Void, Void> Cancel = EventType.cancel("Cancel", UUID.fromString("d7bb2f14-4680-476e-8395-c972a1037589"))
  ;

}
