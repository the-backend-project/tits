package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationData implements DataCreator<Authorisation> {

  @Override
  public Requirements requirements() {
    return Requirements.of(last(Authorisation.class));
  }

  @Override
  public Mono<Authorisation> execute(Input input) {
    var last = input.last(Authorisation.class);
    return Mono.just(last.getUnmarshalledData(Authorisation.class));
  }

}
