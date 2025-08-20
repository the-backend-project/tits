package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationResponseDataCreator.AuthorisationResponseData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationResponseDataCreator implements DataCreator<AcquirerResponse, AuthorisationResponseData> {

  public record AuthorisationResponseData(
    Authorisation authorisation,
    AcquirerResponse acquirerResponse
  ) {}

  @Override
  public Mono<AuthorisationResponseData> execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    return Mono.just(new AuthorisationResponseData(
        eventLog.one(PaymentRequest).getUnmarshalledData(),
        inputEvent.data()
    ));
  }

}
