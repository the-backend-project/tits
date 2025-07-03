package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationDataCreator.AuthorisationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import reactor.core.publisher.Mono;

class AuthorisationRequestUndeliveredData implements DataCreator<AuthorisationData> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved)
    );
  }

  @Override
  public Mono<AuthorisationData> execute(Input input) {
    return Mono.just(new AuthorisationData(
        input.one(PaymentRequest).getUnmarshalledData(PaymentEvent.Authorisation.class),
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class)
    ));
  }
}
