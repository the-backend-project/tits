package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestDataCreator implements DataCreator<CaptureRequestData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest),
        one(PreauthorisationApproved),
        current(CaptureRequest)
    );
  }

  @Override
  public Mono<CaptureRequestData> execute(Input input) {
    var paymentData = input.one(PreauthorisationRequest).getUnmarshalledData(Authorisation.class);
    var bankResponse = input.one(PreauthorisationApproved).getUnmarshalledData(AcquirerResponse.class);
    var captureData = input.current(CaptureRequest).getUnmarshalledData(Capture.class);
    return Mono.just(new CaptureRequestData(paymentData, bankResponse, captureData));
  }

  public record CaptureRequestData(
      Authorisation paymentData,
      AcquirerResponse bankResponse,
      Capture captureData
  ) {}

}
