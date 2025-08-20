package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthenticationDataCreator implements DataCreator<Authorisation, AuthenticationData> {

  @Override
  public Mono<AuthenticationData> execute(InputEvent<Authorisation> inputEvent, EventLog eventLog) {
    return Mono.just(new AuthenticationData(
        inputEvent.data().requestBody(),
        inputEvent.data().simulation()
    ));
  }

  public record AuthenticationData(
      String paymentRequest,
      String simulation
  ) {}

}
