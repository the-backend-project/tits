package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class IsCaptureDataCreator implements DataCreator<Void, Boolean> {

  @Override
  public Requirements requirements() {
    return Requirements.of(one(PaymentRequest));
  }

  @Override
  public Mono<Boolean> execute(InputEvent<Void> inputEvent, Input input) {
    return Mono.just(input.one(PaymentRequest).getUnmarshalledData(Authorisation.class).capture());
  }

}
