package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;

import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;

public class PreauthorisationReversalDataCreator implements DataCreator<Void, PreauthorisationReversalData> {

  public record PreauthorisationReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      Amount amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

  @Override
  public PreauthorisationReversalData execute(InputEvent<Void> inputEvent, EventLog log) {
    var rollbackType = inputEvent.eventType();
    var paymentData = log.one(PaymentRequest);
    AcquirerResponse acquirerResponse = log.lastIfExists(PreauthorisationApproved).orElse(null);
    return new PreauthorisationReversalData(
            false, //rollbackType == Cancel || rollbackType == RollbackRequest,
            false, //rollbackType != Cancel,
            paymentData.t2(),
            paymentData.t1().amount(),
            paymentData.t1().merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            paymentData.t1().simulation()
        );
  }

}
