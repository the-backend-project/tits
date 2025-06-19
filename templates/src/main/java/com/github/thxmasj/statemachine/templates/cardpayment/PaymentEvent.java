package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;
import java.time.ZonedDateTime;
import java.util.UUID;

public class PaymentEvent {

  public record Authorisation(
      Merchant merchant,
      Amount amount,
      String merchantReference,
      Boolean capture,
      Boolean inStore,
      ZonedDateTime transactionTime,
      PaymentToken paymentToken,
      String authenticationReference,
      String authenticationProviderId,
      String cryptogram,
      String simulation
  ) {}

  public record Amount(
      String currency,
      long requested,
      long cashback
  ) {}

  public record Merchant(
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

  public record PaymentToken(
      String pan,
      String psn,
      String expiryDate
  ) {}

  public record Capture(
      String id,
      long amount,
      String stan,
      String authorisationCode,
      String simulation
  ) {}

  public record Refund(
      String id,
      long amount,
      String stan,
      String authorisationCode,
      boolean inStore,
      ZonedDateTime transactionTime
  ) {}

  public enum Type implements EventType {
    AuthenticationFailed(UUID.fromString("ad1dc496-ecdd-4871-9a87-715df7b30aac")),
    PreauthorisationRequest(UUID.fromString("ead7e832-3d32-407b-8316-b23023e8217c"), Authorisation.class),
    PreauthorisationApproved(UUID.fromString("ef315a8e-b9e7-4434-8710-4d238e6ac9c0"), AcquirerResponse.class),
    InvalidPaymentToken(UUID.fromString("778511db-70c0-443b-963a-4e614040256f")),
    AuthorisationRequest(UUID.fromString("4391c1cf-1cda-4627-9d9d-fe8b18b8b6cb"), Authorisation.class),
    AuthorisationAdviceApproved(UUID.fromString("fc91e51a-0b0c-4014-9587-fe6d03af3c46"), AcquirerResponse.class),
    AuthorisationApproved(UUID.fromString("4a3821a0-dbba-448b-8175-c40e4a771df4"), AcquirerResponse.class),
    CaptureRequest(UUID.fromString("b4cef9f9-c9dd-40e4-a627-25ba529aec2e"), Capture.class),
    CaptureRequestedTooLate(UUID.fromString("59016010-cd2f-4144-959c-a5d0d16a6fe9")),
    CaptureApproved(UUID.fromString("6186a241-f9e0-40ae-b444-6ce5e2509dcc"), AcquirerResponse.class),
    RefundRequest(UUID.fromString("4f6d6f15-f8a4-477e-b750-dc52a1f245eb"), Refund.class),
    RefundApproved(UUID.fromString("9c7f8d63-9b27-4bf3-8a90-b7bea56a3fbd"), AcquirerResponse.class),
    AuthorisationExpired(UUID.fromString("8c283db8-742e-4667-a370-45bb0fb3f39e")),
    AcquirerDeclined(UUID.fromString("994388bc-73a4-4334-ba16-41d471ee56b1"), AcquirerResponse.class),
    BankRespondedIncomprehensibly(UUID.fromString("3350b7b6-4a31-45e2-9585-7522a4697497"), AcquirerResponse.class),
    BankRequestFailed(UUID.fromString("3901db73-9647-417b-8a6c-1f81250a03e0"), AcquirerResponse.class),
    RollbackRequest(UUID.fromString("da2d5fd5-beb7-4497-87f7-479ba7eb2a66")) { @Override public boolean isRollback() {return true;} },
    Cancel(UUID.fromString("d7bb2f14-4680-476e-8395-c972a1037589")) { @Override public boolean isCancel() {return true;} },
;

    final UUID id;
    final Class<?> dataType;

    Type(UUID id) {
      this(id, null);
    }

    Type(UUID id, Class<?> dataType) {
      this.id = id;
      this.dataType = dataType;
    }


    @Override
    public UUID id() {
      return id;
    }

    @Override
    public Class<?> dataType() {
      return dataType;
    }

    public boolean isReversible() {
      return this != AuthorisationExpired;
    }

  }

}
