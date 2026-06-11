package com.github.thxmasj.statemachine.templates.cardpayment;

public class AuthorisationReversalDataCreator {

  public record AuthorisationReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference,
      String authorisationCode,
      //Integer acquirerBatchNumber,
      String simulation
  ) {}

}
