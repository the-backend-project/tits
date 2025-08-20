package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthenticationFailedDataCreator
    implements DataCreator<AuthenticationResult, Tuple2<Authorisation, AuthenticationResult>> {

  @Override
  public Mono<Tuple2<Authorisation, AuthenticationResult>> execute(InputEvent<AuthenticationResult> inputEvent, EventLog eventLog) {
    return Mono.just(tuple(
        eventLog.one(PaymentRequest).getUnmarshalledData(),
        inputEvent.data()
    ));
  }

}
