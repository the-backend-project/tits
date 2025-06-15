package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequestedTooLate;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestedTooLateDataCreator.CaptureRequestedTooLateData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestedTooLateDataCreator implements DataCreator<CaptureRequestedTooLateData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest),
        current(CaptureRequestedTooLate)
    );
  }

  @Override
  public Mono<CaptureRequestedTooLateData> execute(Input input) {
    return Mono.just(new CaptureRequestedTooLateData(
            input.one(PreauthorisationRequest).getUnmarshalledData(Authorisation.class),
            input.current(CaptureRequestedTooLate).getUnmarshalledData(Capture.class)
        ));
  }

  public record CaptureRequestedTooLateData(
      Authorisation paymentData,
      Capture captureData
  ) {}

}
