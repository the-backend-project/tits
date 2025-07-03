package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.incomingRequest;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthenticationDataCreator implements DataCreator<AuthenticationData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        incomingRequest(PaymentRequest, String.class),
        one(PaymentRequest)
    );
  }

  @Override
  public Mono<AuthenticationData> execute(Input input) {
    return input.incomingRequest(PaymentRequest, String.class)
        .map(s -> new AuthenticationData(
            s.httpMessage().body(),
            input.one(PaymentRequest).getUnmarshalledData(Authorisation.class).simulation()
        ));
  }

  public record AuthenticationData(
      String paymentRequest,
      String simulation
  ) {}

}
