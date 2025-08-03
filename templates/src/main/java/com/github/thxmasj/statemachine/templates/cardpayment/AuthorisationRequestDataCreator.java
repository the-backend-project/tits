package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationRequestDataCreator implements DataCreator<Void, Tuple2<Authorisation, AuthenticationResult>> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved)
    );
  }

  @Override
  public Mono<Tuple2<Authorisation, AuthenticationResult>> execute(InputEvent<Void> inputEvent, Input input) {
    return Mono.just(tuple(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        input.one(AuthenticationApproved).getUnmarshalledData(AuthenticationResult.class)
    ));
  }

}
