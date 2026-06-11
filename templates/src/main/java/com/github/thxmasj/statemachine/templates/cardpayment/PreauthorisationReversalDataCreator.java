package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;

public class PreauthorisationReversalDataCreator {

  public record PreauthorisationReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      Amount amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

}
