package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class ApprovedCaptureDataCreator implements DataCreator<AcquirerResponse, ApprovedCaptureData> {

  public record ApprovedCaptureData(
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      int netsSessionNumber,
      String stan,
      String authorisationCode,
      String responseCode,
      String requestMessageId
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        incomingRequest(CaptureRequest, String.class)
    );
  }

  @Override
  public Mono<ApprovedCaptureData> execute(InputEvent<AcquirerResponse> inputEvent, Input input) {
    var paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    var inputData = inputEvent.data();
    return input.incomingRequest(CaptureRequest, String.class).map(request -> new ApprovedCaptureData(
        paymentData.merchant().id(),
        paymentData.merchant().aggregatorId(),
        inputData.amount(),
        paymentData.merchantReference(),
        inputData.batchNumber(),
        inputData.stan(),
        inputData.authorisationCode(),
        inputData.responseCode(),
        request.messageId()
    ));
  }

}
