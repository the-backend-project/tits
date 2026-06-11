package com.github.thxmasj.statemachine.templates.cardpayment.validators;

import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount.Invalid;
import com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount.Valid;

public sealed interface ValidatedAmount permits Valid, Invalid {
  record Valid(Amount amount) implements ValidatedAmount {}
  record Invalid(Amount amount, String error) implements ValidatedAmount {}

  default boolean isValid() {
    return this instanceof Valid;
  }

  default boolean isInvalid() {
    return this instanceof Invalid;
  }

  default Invalid invalid() {
    return (Invalid) this;
  }

  static ValidatedAmount validateAmount(Authorisation authorisation) {
    var amount = authorisation.amount();
    if (amount.breakdown() != null) {
      long purchase = amount.breakdown().purchase();
      long cashback = amount.breakdown().cashback();
      if (purchase == 0) {
        return new Invalid(amount, "Missing purchase amount");
      }
      if (cashback != 0 && (!authorisation.capture() || !authorisation.inStore())) {
        return new Invalid(amount, "Cashback allowed for in-store captured payments only");
      }
      if (purchase + cashback != amount.requested()) {
        return new Invalid(authorisation.amount(), "The amount breakdown sum is not equal to the requested amount");
      }
    }
    return new Valid(authorisation.amount());
  }
}

