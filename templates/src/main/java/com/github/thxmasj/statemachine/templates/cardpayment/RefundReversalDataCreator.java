package com.github.thxmasj.statemachine.templates.cardpayment;

public class RefundReversalDataCreator {

  public record RefundReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

}
