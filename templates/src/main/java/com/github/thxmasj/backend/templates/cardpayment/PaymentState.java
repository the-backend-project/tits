package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.State;

public enum PaymentState implements State {
  Rejected,
  ProcessingAuthentication,
  AuthenticationFailed,
  ProcessingAuthorisation,
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  ProcessingRefund,
  AwaitingAcquirerBatchNumber,
  Open,
  ProcessingSettlement,
  Reconciled,
  Error
}
