package com.github.thxmasj.statemachine.templates.cardpayment.validators;

import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime.Invalid;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime.Valid;
import java.time.Duration;
import java.time.ZonedDateTime;

public sealed interface ValidatedTransactionTime permits Valid, Invalid {
  record Valid(ZonedDateTime transactionTime) implements ValidatedTransactionTime {}
  record Invalid(ZonedDateTime transactionTime, String error) implements ValidatedTransactionTime {}

  default boolean isValid() {
    return this instanceof Valid;
  }

  default boolean isInvalid() {
    return this instanceof Invalid;
  }

  default Invalid invalid() {
    return (Invalid) this;
  }

  static ValidatedTransactionTime validateTransactionTime(Authorisation authorisation, ZonedDateTime transitionTime) {
    ZonedDateTime transactionTime = authorisation.transactionTime();
    return Duration.between(transactionTime, transitionTime).abs().compareTo(Duration.ofMinutes(15)) > 0 ?
        new Invalid(transactionTime, "Transaction time out of range") :
        new Valid(transactionTime);
  }
}

