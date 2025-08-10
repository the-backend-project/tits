package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.State;
import java.time.Duration;
import java.util.Optional;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthentication,
  ProcessingAuthorisation(new Timeout(ofMillis(6600), Rollback)),
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund(new Timeout(ofMillis(6600), Rollback)),
  ProcessingSettlement(new Timeout(Duration.ofHours(5), SettlementEvent.Timeout)),
  Reconciled,
  Error
  ;

  private final Timeout timeout;

  PaymentState(Timeout timeout) {
    this.timeout = timeout;
  }

  PaymentState() {
    this(null);
  }

  @Override
  public Optional<Timeout> timeout() {
    return Optional.ofNullable(timeout);
  }

}
