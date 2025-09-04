package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;

public class FailedRefundDataCreator implements DataCreator<String, FailedRefundData> {

  @Override
  public FailedRefundData execute(InputEvent<String> inputEvent, EventLog eventLog) {
    return new FailedRefundData(
        eventLog.one(PaymentRequest),
        eventLog.last(RefundRequest).correlationId()
    );
  }

  public record FailedRefundData(
      Authorisation paymentData,
      String correlationId
  ) {}

}
