package com.github.thxmasj.statemachine.templates.cardpayment;

public record BatchNumber(
    String merchantId,
    Long number
) {

  public BatchNumber next() {
    return new BatchNumber(merchantId, number + 1);
  }

  @Override
  public String toString() {
    return String.format("%s:%d", merchantId, number);
  }

}
