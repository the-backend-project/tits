package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import reactor.core.publisher.Mono;

public class CaptureRequestedTooLateDataCreator implements DataCreator<Capture, Tuple3<Authorisation, AuthenticationResult, Capture>> {

  @Override
  public Mono<Tuple3<Authorisation, AuthenticationResult, Capture>> execute(InputEvent<Capture> inputEvent, EventLog eventLog) {
    return Mono.just(tuple(
            eventLog.one(PaymentRequest).getUnmarshalledData(),
            eventLog.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData(),
            inputEvent.data()
        ));
  }

  public record CaptureRequestedTooLateData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      Capture captureData
  ) {}

}
