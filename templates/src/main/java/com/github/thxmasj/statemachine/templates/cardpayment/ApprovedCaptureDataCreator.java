package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;

public class ApprovedCaptureDataCreator implements DataCreator<AcquirerResponse, ApprovedCaptureData> {

  public record ApprovedCaptureData(
      AcquirerResponse acquirerResponse,
      String merchantId,
      String merchantAggregatorId,
      String merchantReference,
      String correlationId
  ) {}

  @Override
  public ApprovedCaptureData execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    var paymentData = eventLog.one(PaymentRequest);
    var acquirerResponse = inputEvent.data();
    var captureData = eventLog.last(CaptureRequest);
    return new ApprovedCaptureData(
        acquirerResponse,
        paymentData.merchant().id(),
        paymentData.merchant().aggregatorId(),
        paymentData.merchantReference(),
        captureData.correlationId()
    );
  }

}
