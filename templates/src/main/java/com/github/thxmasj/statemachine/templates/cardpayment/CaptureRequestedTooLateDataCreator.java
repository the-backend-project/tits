package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestedTooLateDataCreator.CaptureRequestedTooLateData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestedTooLateDataCreator implements DataCreator<CaptureRequestedTooLateData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved),
        current(CaptureRequest)
    );
  }

  @Override
  public Mono<CaptureRequestedTooLateData> execute(Input input) {
    return Mono.just(new CaptureRequestedTooLateData(
            input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
            input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class),
            input.current(CaptureRequest).getUnmarshalledData(Capture.class)
        ));
  }

  public record CaptureRequestedTooLateData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      Capture captureData
  ) {}

}
