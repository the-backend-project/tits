package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class DeclinedRefundDataCreator implements DataCreator<DeclinedRefundDataCreator.DeclinedRefundData> {

  @Override
  public Mono<DeclinedRefundData> execute(Input input) {
    Authorisation evaluatedPaymentRequest = input.one(PreauthorisationRequest, AuthorisationRequest)
        .getUnmarshalledData(Authorisation.class);
    return input.incomingRequest(RefundRequest, String.class)
        .map(request -> new DeclinedRefundData(evaluatedPaymentRequest, request.messageId()));
  }

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest, AuthorisationRequest),
        incomingRequest(RefundRequest, String.class)
    );
  }

  public record DeclinedRefundData(
      Authorisation paymentData,
      String requestMessageId
  ) {}

}
