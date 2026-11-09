package com.github.thxmasj.statemachine.templates.cardpayment;

public record AcquirerBatchNumber(
   String merchantId,
   int number
) {

  public AcquirerBatchNumber next() {
    return new AcquirerBatchNumber(merchantId, number >= 999 ? 1 : number + 1);
  }

  @Override
  public String toString() {
    return String.format("%s:%s", merchantId, number);
  }
}
