package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.Choice;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import java.time.Duration;
import java.util.Optional;
import reactor.core.publisher.Mono;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthentication,
  PaymentChoice(
      (Choice<AuthenticationResult, Boolean>) (isCapture, _, _, context, _) ->
          Mono.just(context.output(isCapture ? AuthorisationRequest : PreauthorisationRequest, null))
  ),
  ProcessingAuthorisation(new Timeout(ofMillis(6600), Rollback)),
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund(new Timeout(ofMillis(6600), Rollback)),
  ProcessingSettlement(new Timeout(Duration.ofHours(5), SettlementEvent.Type.Timeout)),
  Reconciled,
  Error
  ;

  private final Timeout timeout;
  private final Choice<?, ?> choice;

  PaymentState(
      Timeout timeout,
      Choice<?, ?> choice
  ) {
    this.timeout = timeout;
    this.choice = choice;
  }

  PaymentState(Timeout timeout) {
    this(timeout, null);
  }

  PaymentState(Choice<?, ?> choice) {
    this(null, choice);
  }

  PaymentState() {
    this(null, null);
  }

  @Override
  public Choice<?, ?> choice() {
    return choice;
  }

  @Override
  public Optional<Timeout> timeout() {
    return Optional.ofNullable(timeout);
  }

}
