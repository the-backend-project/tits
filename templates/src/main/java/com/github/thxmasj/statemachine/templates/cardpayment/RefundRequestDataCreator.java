package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;

public class RefundRequestDataCreator implements DataCreator<Refund, RefundRequestData> {

  @Override
  public RefundRequestData execute(InputEvent<Refund> inputEvent, EventLog eventLog) {
    Authorisation authorisationData = eventLog.one(PaymentRequest);
    long alreadyCapturedAmount;
    if (authorisationData.capture()) {
      alreadyCapturedAmount = authorisationData.amount().requested();
    } else {
      alreadyCapturedAmount = eventLog.all(CaptureApproved).stream()
          .map(AcquirerResponse::amount)
          .mapToLong(Long::longValue)
          .sum();
    }
    long alreadyRefundedAmount = eventLog.all(RefundApproved).stream()
        .map(AcquirerResponse::amount)
        .mapToLong(Long::longValue)
        .sum();
    return new RefundRequestData(
        authorisationData,
        eventLog.one(AuthorisationRequest, PreauthorisationRequest),
        inputEvent.data(),
        alreadyCapturedAmount,
        alreadyRefundedAmount,
        inputEvent.data().simulation()
    );
  }

  public record RefundRequestData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      Refund refundData,
      long alreadyCapturedAmount,
      long alreadyRefundedAmount,
      String simulation
  ) {}
}
