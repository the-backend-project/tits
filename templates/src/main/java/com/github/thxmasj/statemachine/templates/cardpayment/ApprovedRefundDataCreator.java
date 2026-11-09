package com.github.thxmasj.statemachine.templates.cardpayment;

public class ApprovedRefundDataCreator {

    public record ApprovedRefundData(
      AcquirerResponse acquirerResponse,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference
  ) {}

}
