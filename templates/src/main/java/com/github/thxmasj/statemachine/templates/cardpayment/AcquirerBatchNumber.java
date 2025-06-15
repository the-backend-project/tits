package com.github.thxmasj.statemachine.templates.cardpayment;

public record AcquirerBatchNumber(
   String merchantId,
   int number
) {}
