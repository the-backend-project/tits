package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;

public class ApprovedCaptureDataCreator implements DataCreator<AcquirerResponse, ApprovedCaptureData> {

  public record ApprovedCaptureData(
      AcquirerResponse acquirerResponse,
      PaymentEvent.Merchant merchant,
      String merchantReference
  ) {}

  @Override
  public ApprovedCaptureData execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    var paymentData = eventLog.one(PaymentRequest);
    var acquirerResponse = inputEvent.data();
    return new ApprovedCaptureData(
        acquirerResponse,
        paymentData.t2(),
        paymentData.t1().merchantReference()
    );
  }

}
