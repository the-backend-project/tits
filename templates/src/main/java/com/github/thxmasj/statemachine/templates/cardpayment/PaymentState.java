package com.github.thxmasj.statemachine.templates.cardpayment;

import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.State;
import java.time.Duration;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthentication,
  ProcessingAuthorisation {@Override public Timeout<?> timeout() {return rollbackAfter(ofMillis(6600), 2);}},
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund {@Override public Timeout<?> timeout() {return rollbackAfter(ofMillis(6600));}},
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
