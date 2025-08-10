package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.lastIfExists;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class AuthorisationReversalDataCreator implements DataCreator<Long, AuthorisationReversalData> {

  public record AuthorisationReversalData(
      HttpRequestMessage originalRequest,
      boolean clientOriginated,
      boolean technicalReversal,
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      String authorisationCode,
      Integer acquirerBatchNumber,
      String simulation
  ) {}

  @Override
  public Requirements requirements() {
    return Requirements.of(
        outgoingRequest(Acquirer, AuthorisationRequest, String.class),
        one(PaymentRequest),
        lastIfExists(AuthorisationApproved)
    );
  }

  @Override
  public Mono<AuthorisationReversalData> execute(InputEvent<Long> inputEvent, Input input) {
    Authorisation paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    AcquirerResponse acquirerResponse = input.lastIfExists(AuthorisationApproved)
        .map(e -> e.getUnmarshalledData(AcquirerResponse.class)).orElse(null);
    return input.outgoingRequest(Acquirer, AuthorisationRequest, String.class)
        .map(originalRequest -> new AuthorisationReversalData(
            originalRequest.httpMessage(),
            inputEvent.eventType() == Cancel || inputEvent.eventType() == RollbackRequest,
            inputEvent.eventType() != Cancel,
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            paymentData.amount().requested(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            acquirerResponse != null ? acquirerResponse.batchNumber() : 1,
            paymentData.simulation()
        ));
  }

}
