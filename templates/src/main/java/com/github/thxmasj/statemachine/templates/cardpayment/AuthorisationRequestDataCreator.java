package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidAuthenticationToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidPaymentTokenOwnership;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidPaymentTokenStatus;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationRequestDataCreator.AuthorisationRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationRequestDataCreator implements DataCreator<AuthorisationRequestData> {

  public record AuthorisationRequestData(
      Authorisation authorisation,
      AuthenticationResult authenticationResult
  ) {}

  @Override
  public Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        one(AuthenticationApproved, AuthenticationFailed, InvalidPaymentTokenOwnership, InvalidPaymentTokenStatus, InvalidAuthenticationToken)
    );
  }

  @Override
  public Mono<AuthorisationRequestData> execute(Input input) {
    return Mono.just(new AuthorisationRequestData(
        input.one(PaymentRequest).getUnmarshalledData(Authorisation.class),
        input.one(AuthenticationApproved, AuthenticationFailed, InvalidPaymentTokenOwnership, InvalidPaymentTokenStatus, InvalidAuthenticationToken)
            .getUnmarshalledData(AuthenticationResult.class)
    ));
  }

}
