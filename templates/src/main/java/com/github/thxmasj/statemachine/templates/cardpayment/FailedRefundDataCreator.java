package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import reactor.core.publisher.Mono;

public class FailedRefundDataCreator implements DataCreator<Void, FailedRefundData> {

  @Override
  public Mono<FailedRefundData> execute(InputEvent<Void> inputEvent, Input input) {
    return Mono.just(new FailedRefundData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        input.last(RefundRequest).getUnmarshalledData(Refund.class).correlationId()
    ));
  }

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        last(RefundRequest)
    );
  }

  public record FailedRefundData(
      Authorisation paymentData,
      String correlationId
  ) {}

}
