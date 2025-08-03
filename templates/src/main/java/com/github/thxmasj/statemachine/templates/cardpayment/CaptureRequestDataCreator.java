package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.all;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestDataCreator implements DataCreator<Capture, CaptureRequestData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved),
        one(PreauthorisationApproved),
        all(CaptureApproved)

    );
  }

  @Override
  public Mono<CaptureRequestData> execute(InputEvent<Capture> inputEvent, Input input) {
//    long authorisedAmount = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class).amount().requested();
//    long alreadyCapturedAmount = input.all(CaptureApproved).stream()
//        .map(event -> event.getUnmarshalledData(AcquirerResponse.class))
//        .map(AcquirerResponse::amount)
//        .mapToLong(Long::longValue)
//        .sum();
//    if (alreadyCapturedAmount + captureRequest.getAmount().getValue() <= authorisedAmount) {
//      return Mono.just(context.validRequest(data));
//    } else {
//      return Mono.just(context.invalidRequest("Capture amount too large"));
//    }
    return Mono.just(new CaptureRequestData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class),
        input.one(PreauthorisationApproved).getUnmarshalledData(AcquirerResponse.class),
        inputEvent.data(),
        input.all(CaptureApproved).stream()
            .map(event -> event.getUnmarshalledData(AcquirerResponse.class))
            .map(AcquirerResponse::amount)
            .mapToLong(Long::longValue)
            .sum()
    ));
  }

  public record CaptureRequestData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      AcquirerResponse bankResponse,
      Capture captureData,
      long alreadyCapturedAmount
  ) {}

}
