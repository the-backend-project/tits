package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestedTooLateDataCreator.CaptureRequestedTooLateData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestedTooLateDataCreator implements DataCreator<Capture, CaptureRequestedTooLateData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthorisationRequest, PreauthorisationRequest)
    );
  }

  @Override
  public Mono<CaptureRequestedTooLateData> execute(InputEvent<Capture> inputEvent, Input input) {
    return Mono.just(new CaptureRequestedTooLateData(
            input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
            input.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData(AuthenticationResult.class),
            inputEvent.data()
        ));
  }

  public record CaptureRequestedTooLateData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      Capture captureData
  ) {}

}
