package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.all;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundApproved;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import reactor.core.publisher.Mono;

public class RefundRequestDataCreator implements DataCreator<Refund, RefundRequestData> {

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved),
        all(CaptureApproved),
        all(RefundApproved)
    );
  }

  @Override
  public Mono<RefundRequestData> execute(InputEvent<Refund> inputEvent, Input input) {
    Authorisation authorisationData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    long alreadyCapturedAmount;
    if (authorisationData.capture()) {
      alreadyCapturedAmount = authorisationData.amount().requested();
    } else {
      alreadyCapturedAmount = input.all(CaptureApproved).stream()
          .map(event -> event.getUnmarshalledData(AcquirerResponse.class))
          .map(AcquirerResponse::amount)
          .mapToLong(Long::longValue)
          .sum();
    }
    long alreadyRefundedAmount = input.all(RefundApproved).stream()
        .map(event -> event.getUnmarshalledData(AcquirerResponse.class))
        .map(AcquirerResponse::amount)
        .mapToLong(Long::longValue)
        .sum();
    return Mono.just(new RefundRequestData(
        authorisationData,
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class),
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
