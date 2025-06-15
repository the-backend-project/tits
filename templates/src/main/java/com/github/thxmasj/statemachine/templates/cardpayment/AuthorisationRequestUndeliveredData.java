package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.of;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.*;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import reactor.core.publisher.Mono;

class AuthorisationRequestUndeliveredData implements DataCreator<PaymentEvent.Authorisation> {

  @Override
  public Requirements requirements() {
    return of(one(AuthorisationRequest));
  }

  @Override
  public Mono<PaymentEvent.Authorisation> execute(Input input) {
    return Mono.just(input.one(AuthorisationRequest).getUnmarshalledData(PaymentEvent.Authorisation.class));
  }
}
