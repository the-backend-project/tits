package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;

public class AuthorisationReversalDataCreator implements DataCreator<Void, AuthorisationReversalData> {

  public record AuthorisationReversalData(
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
        outgoingRequest(Acquirer, AuthorisationRequest, String.class)
    );
  }

  @Override
  public AuthorisationReversalData execute(InputEvent<Void> inputEvent, EventLog eventLog) {
    Authorisation paymentData = eventLog.one(PaymentRequest);
    AcquirerResponse acquirerResponse = eventLog.lastIfExists(AuthorisationApproved).orElse(null);
    return new AuthorisationReversalData(
            inputEvent.eventType() == Cancel || inputEvent.eventType() == RollbackRequest,
            inputEvent.eventType() != Cancel,
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            paymentData.amount().requested(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            acquirerResponse != null ? acquirerResponse.batchNumber() : 1,
            paymentData.simulation()
        );
  }

}
