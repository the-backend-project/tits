package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
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
    return input.incomingRequest(RefundRequest, String.class).map(request -> new FailedRefundData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        request.messageId()
    ));
  }

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        incomingRequest(RefundRequest, String.class)
    );
  }

  public record FailedRefundData(
      Authorisation paymentData,
      String requestMessageId
  ) {}

}
