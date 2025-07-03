package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestDataCreator implements DataCreator<CaptureRequestData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved),
        one(PreauthorisationApproved),
        current(CaptureRequest)
    );
  }

  @Override
  public Mono<CaptureRequestData> execute(Input input) {
    return Mono.just(new CaptureRequestData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class),
        input.one(PreauthorisationApproved).getUnmarshalledData(AcquirerResponse.class),
        input.current(CaptureRequest).getUnmarshalledData(Capture.class)
    ));
  }

  public record CaptureRequestData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      AcquirerResponse bankResponse,
      Capture captureData
  ) {}

}
