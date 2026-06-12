package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.http.inbox.HttpInbox.EventReference;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;

public class RefundRequestDataCreator {

  public record RefundRequestData(
      Authorisation authorisationData,
      PaymentEvent.Merchant merchant,
      AuthenticationResult authenticationResult,
      PaymentToken paymentToken,
      Refund refundData,
      long alreadyCapturedAmount,
      long alreadyRefundedAmount,
      String simulation,
      EventReference eventReference
  ) {}
}
