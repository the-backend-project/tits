package com.github.thxmasj.backend.templates.cardpayment;

public class ApprovedRefundDataCreator {

    public record ApprovedRefundData(
      AcquirerResponse acquirerResponse,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference
  ) {}

}
