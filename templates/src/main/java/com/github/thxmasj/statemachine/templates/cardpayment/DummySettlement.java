package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.ApprovedCutOff;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Reconciliation;

public class DummySettlement extends AbstractSettlement {

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateSettlementResponse() {
    return null;
  }

  @Override
  protected OutgoingRequests.Reconciliation reconciliation() {
    return new Reconciliation() {};
  }

  @Override
  protected OutgoingRequests.ApprovedCutOff approvedCutOff() {
    return new ApprovedCutOff() {};
  }
}
