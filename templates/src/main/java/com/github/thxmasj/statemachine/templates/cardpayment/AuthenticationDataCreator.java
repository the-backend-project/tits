package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;

public class AuthenticationDataCreator
    implements DataCreator<Tuple2<Authorisation, String>, Tuple2<Authorisation, AuthenticationData>> {

  @Override
  public Tuple2<Authorisation, AuthenticationData> execute(
      InputEvent<Tuple2<Authorisation, String>> inputEvent,
      EventLog eventLog
  ) {
    return tuple(
        inputEvent.data().t1(),
        new AuthenticationData(inputEvent.data().t2(), inputEvent.data().t1().simulation())
    );
  }

  public record AuthenticationData(
      String paymentRequest,
      String simulation
  ) {}

}
