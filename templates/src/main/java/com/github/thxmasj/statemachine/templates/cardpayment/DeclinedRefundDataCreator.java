package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class DeclinedRefundDataCreator implements DataCreator<AcquirerResponse, DeclinedRefundDataCreator.DeclinedRefundData> {

  @Override
  public Mono<DeclinedRefundData> execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    return Mono.just(new DeclinedRefundData(
            eventLog.one(PaymentRequest),
            inputEvent.data(),
            eventLog.last(RefundRequest).correlationId()
        ));
  }

  public record DeclinedRefundData(
      Authorisation paymentData,
      AcquirerResponse responseData,
      String correlationId
  ) {}

}
