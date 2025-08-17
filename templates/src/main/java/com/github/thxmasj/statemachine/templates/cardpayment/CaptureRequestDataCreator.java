package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestDataCreator implements DataCreator<Capture, CaptureRequestData> {

  @Override
  public Mono<CaptureRequestData> execute(InputEvent<Capture> inputEvent, EventLog eventLog, Input unused) {
    return Mono.just(new CaptureRequestData(
        eventLog.one(PaymentRequest).getUnmarshalledData(),
        eventLog.one(PreauthorisationRequest).getUnmarshalledData(),
        eventLog.one(PreauthorisationApproved).getUnmarshalledData(),
        inputEvent.data(),
        eventLog.all(CaptureApproved).stream()
            .map(Event::getUnmarshalledData)
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
