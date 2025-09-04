package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.DeclinedRefundDataCreator.DeclinedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;

public class DeclinedRefundDataCreator implements DataCreator<AcquirerResponse, DeclinedRefundData> {

  @Override
  public DeclinedRefundData execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    return new DeclinedRefundData(
            eventLog.one(PaymentRequest),
            inputEvent.data(),
            eventLog.last(RefundRequest).correlationId()
        );
  }

  public record DeclinedRefundData(
      Authorisation paymentData,
      AcquirerResponse responseData,
      String correlationId
  ) {}

}
