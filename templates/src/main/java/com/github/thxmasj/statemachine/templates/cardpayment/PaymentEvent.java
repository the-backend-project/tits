package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;
import java.time.ZonedDateTime;

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
    AuthenticationFailed(1002),
    PreauthorisationRequest(1005, Authorisation.class),
    PreauthorisationApproved(1007, AcquirerResponse.class),
    InvalidPaymentToken(1009),
    AuthorisationRequest(1011, Authorisation.class),
    AuthorisationAdviceApproved(1012, AcquirerResponse.class),
    AuthorisationApproved(1013, AcquirerResponse.class),
    CaptureRequest(1014, Capture.class),
    CaptureRequestedTooLate(1015),
    CaptureApproved(1017, AcquirerResponse.class),
    RefundRequest(1018, Refund.class),
    RefundApproved(1020, AcquirerResponse.class),
    AuthorisationExpired(1021),
    AcquirerDeclined(1022, AcquirerResponse.class),
    BankRespondedIncomprehensibly(1023, AcquirerResponse.class),
    BankRequestFailed(1025, AcquirerResponse.class),
    RollbackRequest(1033) { @Override public boolean isRollback() {return true;} },
    Cancel(1032) { @Override public boolean isCancel() {return true;} },
;

    final int id;
    final Class<?> dataType;

    Type(int id) {
      this(id, null);
    }

    Type(int id, Class<?> dataType) {
      this.id = id;
      this.dataType = dataType;
    }


    @Override
    public int id() {
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
