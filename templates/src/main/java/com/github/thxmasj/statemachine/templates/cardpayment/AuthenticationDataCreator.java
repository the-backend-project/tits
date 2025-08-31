package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

import static com.github.thxmasj.statemachine.Tuples.tuple;

public class AuthenticationDataCreator
    implements DataCreator<Tuple2<Authorisation, String>, Tuple2<Authorisation, AuthenticationData>> {

  @Override
  public Mono<Tuple2<Authorisation, AuthenticationData>> execute(
      InputEvent<Tuple2<Authorisation, String>> inputEvent,
      EventLog eventLog
  ) {
    return Mono.just(tuple(
        inputEvent.data().t1(),
        new AuthenticationData(inputEvent.data().t2(), inputEvent.data().t1().simulation())
    ));
  }

  public record AuthenticationData(
      String paymentRequest,
      String simulation
  ) {}

}
