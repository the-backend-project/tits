package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.trigger;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import reactor.core.publisher.Mono;

public class RefundRequestDataCreator implements DataCreator<RefundRequestData> {

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest, AuthorisationRequest),
        incomingRequest(RefundRequest, String.class),
        trigger(RefundRequest)
    );
  }

  @Override
  public Mono<RefundRequestData> execute(Input input) {
    var evaluatedPaymentRequest = input.one(PreauthorisationRequest, AuthorisationRequest)
        .getUnmarshalledData(Authorisation.class);
    return input.incomingRequest(RefundRequest, String.class)
        .map(refundRequest -> new RefundRequestData(
                evaluatedPaymentRequest,
                input.trigger(RefundRequest).getUnmarshalledData(Refund.class),
                refundRequest.httpMessage().headerValue("x-simulation")
            )
        );
  }

  public record RefundRequestData(
      Authorisation paymentData,
      Refund refundData,
      String simulation
  ) {}
}
