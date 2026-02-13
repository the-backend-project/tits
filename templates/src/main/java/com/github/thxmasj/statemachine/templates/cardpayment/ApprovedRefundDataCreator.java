package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;

public class ApprovedRefundDataCreator implements DataCreator<AcquirerResponse, ApprovedRefundData> {

    public record ApprovedRefundData(
      AcquirerResponse acquirerResponse,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference,
      String correlationId
  ) {}

  @Override
  public ApprovedRefundData execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    var paymentData = eventLog.one(PaymentRequest);
    AcquirerResponse acquirerResponse = inputEvent.data();
    Refund refundData = eventLog.last(RefundRequest);
    return new ApprovedRefundData(
        acquirerResponse,
        paymentData.t2(),
        refundData.amount(),
        paymentData.t1().merchantReference(),
        refundData.correlationId()
    );
  }

}
