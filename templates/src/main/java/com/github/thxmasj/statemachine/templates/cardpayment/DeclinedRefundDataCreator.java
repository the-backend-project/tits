package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class DeclinedRefundDataCreator implements DataCreator<AcquirerResponse, DeclinedRefundDataCreator.DeclinedRefundData> {

  @Override
  public Mono<DeclinedRefundData> execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog, Input input) {
    return Mono.just(new DeclinedRefundData(
            eventLog.one(PaymentRequest).getUnmarshalledData(),
            inputEvent.data(),
            eventLog.last(RefundRequest).getUnmarshalledData().correlationId()
        ));
  }

  public record DeclinedRefundData(
      Authorisation paymentData,
      AcquirerResponse responseData,
      String correlationId
  ) {}

}
