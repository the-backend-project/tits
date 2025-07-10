package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationRequestDataCreator.AuthorisationRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import reactor.core.publisher.Mono;

class AuthorisationRequestUndeliveredData implements DataCreator<AuthorisationRequestData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved)
    );
  }

  @Override
  public Mono<AuthorisationRequestData> execute(Input input) {
    return Mono.just(new AuthorisationRequestData(
        input.one(PaymentRequest).getUnmarshalledData(PaymentEvent.Authorisation.class),
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class)
    ));
  }
}
