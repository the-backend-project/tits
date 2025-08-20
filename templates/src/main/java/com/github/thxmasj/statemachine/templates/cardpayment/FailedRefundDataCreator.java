package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class FailedRefundDataCreator implements DataCreator<Void, FailedRefundData> {

  @Override
  public Mono<FailedRefundData> execute(InputEvent<Void> inputEvent, EventLog eventLog) {
    return Mono.just(new FailedRefundData(
        eventLog.one(PaymentRequest).getUnmarshalledData(),
        eventLog.last(RefundRequest).getUnmarshalledData().correlationId()
    ));
  }

  public record FailedRefundData(
      Authorisation paymentData,
      String correlationId
  ) {}

}
