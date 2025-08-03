package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthenticationFailedDataCreator
    implements DataCreator<AuthenticationResult, Tuple2<Authorisation, AuthenticationResult>> {

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest)
    );
  }

  @Override
  public Mono<Tuple2<Authorisation, AuthenticationResult>> execute(InputEvent<AuthenticationResult> inputEvent, Input input) {
    return Mono.just(tuple(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        inputEvent.data()
    ));
  }

}
