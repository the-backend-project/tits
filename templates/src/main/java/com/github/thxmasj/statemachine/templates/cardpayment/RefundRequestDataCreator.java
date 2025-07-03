package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.trigger;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import reactor.core.publisher.Mono;

public class RefundRequestDataCreator implements DataCreator<RefundRequestData> {

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved),
        incomingRequest(RefundRequest, String.class),
        trigger(RefundRequest)
    );
  }

  @Override
  public Mono<RefundRequestData> execute(Input input) {
    return input.incomingRequest(RefundRequest, String.class)
        .map(refundRequest -> new RefundRequestData(
                input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
                input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class),
                input.trigger(RefundRequest).getUnmarshalledData(Refund.class),
                refundRequest.httpMessage().headerValue("x-simulation")
            )
        );
  }

  public record RefundRequestData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      Refund refundData,
      String simulation
  ) {}
}
