package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import reactor.core.publisher.Mono;

public class RefundRequestDataCreator implements DataCreator<Refund, RefundRequestData> {

  @Override
  public Mono<RefundRequestData> execute(InputEvent<Refund> inputEvent, EventLog eventLog, Input unused) {
    Authorisation authorisationData = eventLog.one(PaymentRequest).getUnmarshalledData();
    long alreadyCapturedAmount;
    if (authorisationData.capture()) {
      alreadyCapturedAmount = authorisationData.amount().requested();
    } else {
      alreadyCapturedAmount = eventLog.all(CaptureApproved).stream()
          .map(Event::getUnmarshalledData)
          .map(AcquirerResponse::amount)
          .mapToLong(Long::longValue)
          .sum();
    }
    long alreadyRefundedAmount = eventLog.all(RefundApproved).stream()
        .map(Event::getUnmarshalledData)
        .map(AcquirerResponse::amount)
        .mapToLong(Long::longValue)
        .sum();
    return Mono.just(new RefundRequestData(
        authorisationData,
        eventLog.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData(),
        inputEvent.data(),
        alreadyCapturedAmount,
        alreadyRefundedAmount,
        inputEvent.data().simulation()
    ));
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
