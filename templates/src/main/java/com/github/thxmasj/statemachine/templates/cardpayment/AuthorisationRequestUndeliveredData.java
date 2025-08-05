package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

class AuthorisationRequestUndeliveredData implements DataCreator<Void, Tuple2<Authorisation, AuthenticationResult>> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthorisationRequest, PreauthorisationRequest)
    );
  }

  @Override
  public Mono<Tuple2<Authorisation, AuthenticationResult>> execute(InputEvent<Void> inputEvent, Input input) {
    return Mono.just(tuple(
        input.one(PaymentRequest).getUnmarshalledData(PaymentEvent.Authorisation.class),
        input.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData(AuthenticationResult.class)
    ));
  }
}
