package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentToken;

public class DummyPaymentTransitions extends PaymentTransitions{

  @Override
  protected PaymentToken paymentToken(String encryptedAuthenticationData) {
    return null;
  }

}
