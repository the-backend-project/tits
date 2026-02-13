package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import java.time.Duration;
import java.util.Optional;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthentication,
  ProcessingAuthorisation(new Timeout(ofMillis(6600), new InputEvent<>(Rollback, new Data(-1, "ProcessingAuthorisation tímeout")))),
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund(new Timeout(ofMillis(6600), new InputEvent<>(Rollback, new Data(-1, "ProcessingRefund tímeout")))),
  ProcessingSettlement(new Timeout(Duration.ofHours(5), new InputEvent<>(SettlementEvent.Timeout, null))),
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
