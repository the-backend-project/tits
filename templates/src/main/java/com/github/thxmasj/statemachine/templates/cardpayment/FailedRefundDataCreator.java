package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class FailedRefundDataCreator implements DataCreator<FailedRefundData> {

  @Override
  public Mono<FailedRefundData> execute(Input input) {
    Authorisation evaluatedPaymentRequest = input.one(PreauthorisationRequest, AuthorisationRequest)
        .getUnmarshalledData(Authorisation.class);
    return input.incomingRequest(RefundRequest, String.class)
        .map(request -> new FailedRefundData(evaluatedPaymentRequest, request.messageId()));
  }

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest, AuthorisationRequest),
        incomingRequest(RefundRequest, String.class)
    );
  }

  public record FailedRefundData(
      Authorisation paymentData,
      String requestMessageId
  ) {}

}
