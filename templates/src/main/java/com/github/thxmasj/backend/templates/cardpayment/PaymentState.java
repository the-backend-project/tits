package com.github.thxmasj.backend.templates.cardpayment;

import static java.time.Duration.ofMillis;

import com.github.thxmasj.backend.core.State;
import java.time.Duration;

public enum PaymentState implements State {
  Rejected,
  ProcessingAuthentication,
  AuthenticationFailed,
  ProcessingAuthorisation {@Override public Timeout<?> timeout() {return rollbackAfter(ofMillis(6600), 2);}},
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  ProcessingRefund {@Override public Timeout<?> timeout() {return rollbackAfter(ofMillis(6600));}},
  AwaitingAcquirerBatchNumber,
  Open,
  ProcessingSettlement {
    @Override
    public Timeout<?> timeout() {
      return new Timeout<>(Duration.ofHours(5), SettlementEvent.Timeout, _ -> null);
    }
  },
  Reconciled,
  Error
}
