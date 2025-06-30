package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.Choice;
import com.github.thxmasj.statemachine.State;
import java.time.Duration;
import java.util.Optional;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthorisation(new Timeout(ofMillis(6600), Rollback)),
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund(new Timeout(ofMillis(6600), Rollback)),
  ProcessingSettlement(new Timeout(Duration.ofHours(5), SettlementEvent.Type.Timeout)),
  Settled(new Reconcile()),
  Reconciled,
  Error
  ;

  private final Timeout timeout;
  private final Choice<?> choice;

  PaymentState(
      Timeout timeout,
      Choice<?> choice
  ) {
    this.timeout = timeout;
    this.choice = choice;
  }

  PaymentState(Timeout timeout) {
    this(timeout, null);
  }

  PaymentState(Choice<?> choice) {
    this(null, choice);
  }

  PaymentState() {
    this(null, null);
  }

  @Override
  public Choice<?> choice() {
    return choice;
  }

  @Override
  public Optional<Timeout> timeout() {
    return Optional.ofNullable(timeout);
  }

}
