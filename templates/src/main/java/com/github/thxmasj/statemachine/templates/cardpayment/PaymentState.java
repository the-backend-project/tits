package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static java.time.Duration.ofMillis;

import com.github.thxmasj.statemachine.Choice;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Optional;

public enum PaymentState implements State {
  Begin,
  ProcessingAuthentication,
  PaymentChoice(new Choice<Void>() {
    @Override
    public Requirements requirements() {
      return Requirements.of(one(PaymentRequest));
    }

    @Override
    public Mono<InputEvent<Void>> execute(EntityId entityId, Context<Void> context, Input input) {
      return Mono.just(context.output(
          input.one(PaymentRequest).getUnmarshalledData(Authorisation.class).capture() ?
              AuthorisationRequest :
              PreauthorisationRequest,
          null
      ));
    }
  }),
  ProcessingAuthorisation(new Timeout(ofMillis(6600), Rollback)),
  AuthorisationFailed,
  ProcessingCapture,
  Preauthorised,
  Authorised,
  Expired,
  ExpiredAfterCapture,
  ProcessingRefund(new Timeout(ofMillis(6600), Rollback)),
  ProcessingSettlement(new Timeout(Duration.ofHours(5), SettlementEvent.Type.Timeout)),
  Settled(new Reconcile()),
  Reconciled,
  Error
  ;

  private final Timeout timeout;
  private final Choice<?> choice;

  PaymentState(
      Timeout timeout,
      Choice<?> choice
  ) {
    this.timeout = timeout;
    this.choice = choice;
  }

  PaymentState(Timeout timeout) {
    this(timeout, null);
  }

  PaymentState(Choice<?> choice) {
    this(null, choice);
  }

  PaymentState() {
    this(null, null);
  }

  @Override
  public Choice<?> choice() {
    return choice;
  }

  @Override
  public Optional<Timeout> timeout() {
    return Optional.ofNullable(timeout);
  }

}
