package com.github.thxmasj.statemachine.templates.cardpayment;

public record AcquirerAuthorisationRequest(
    String pan,
    String psn,
    String expiryDate,
    String currency,
    long amount,
    int batchNumber,
    String merchantReference,
    String cryptogram
) {}
