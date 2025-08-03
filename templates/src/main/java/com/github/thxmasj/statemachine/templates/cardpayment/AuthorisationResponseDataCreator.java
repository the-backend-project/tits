package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationResponseDataCreator.AuthorisationResponseData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationResponseDataCreator implements DataCreator<AcquirerResponse, AuthorisationResponseData> {

  public record AuthorisationResponseData(
    Authorisation authorisation,
    AcquirerResponse acquirerResponse
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest)
    );
  }

  @Override
  public Mono<AuthorisationResponseData> execute(InputEvent<AcquirerResponse> inputEvent, Input input) {
    return Mono.just(new AuthorisationResponseData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        inputEvent.data()
    ));
  }

}
