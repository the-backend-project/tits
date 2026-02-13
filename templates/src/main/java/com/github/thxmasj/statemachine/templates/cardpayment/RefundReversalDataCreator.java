package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;

public class RefundReversalDataCreator implements DataCreator<Void, RefundReversalData> {

  public record RefundReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

  @Override
  public RefundReversalData execute(InputEvent<Void> inputEvent, EventLog log) {
    var rollbackType = inputEvent.eventType();
    var paymentData = log.one(PaymentRequest);
    Refund refundData = log.last(RefundRequest);
    AcquirerResponse acquirerResponse = log.lastIfExists(RefundApproved).orElse(null);
    return new RefundReversalData(
            false, //rollbackType == Cancel || rollbackType == RollbackRequest,
            false, //rollbackType != Cancel,
            paymentData.t2(),
            refundData.amount(),
            paymentData.t1().merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            paymentData.t1().simulation()
        );
  }

}
