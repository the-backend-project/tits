package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class ApprovedCaptureDataCreator implements DataCreator<AcquirerResponse, ApprovedCaptureData> {

  public record ApprovedCaptureData(
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      int acquirerBatchNumber,
      String stan,
      String authorisationCode,
      String responseCode,
      String correlationId
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        last(CaptureRequest)
    );
  }

  @Override
  public Mono<ApprovedCaptureData> execute(InputEvent<AcquirerResponse> inputEvent, Input input) {
    var paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    var acquirerResponse = inputEvent.data();
    var captureData = input.last(CaptureRequest).getUnmarshalledData(Capture.class);
    return Mono.just(new ApprovedCaptureData(
        paymentData.merchant().id(),
        paymentData.merchant().aggregatorId(),
        acquirerResponse.amount(),
        paymentData.merchantReference(),
        acquirerResponse.batchNumber(),
        acquirerResponse.stan(),
        acquirerResponse.authorisationCode(),
        acquirerResponse.responseCode(),
        captureData.correlationId()
    ));
  }

}
