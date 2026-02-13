package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;

public class AuthorisationReversalDataCreator implements DataCreator<Void, AuthorisationReversalData> {

  public record AuthorisationReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      PaymentEvent.Merchant merchant,
      long amount,
      String merchantReference,
      String authorisationCode,
      //Integer acquirerBatchNumber,
      String simulation
  ) {}

  @Override
  public AuthorisationReversalData execute(
      InputEvent<Void> inputEvent,
      EventLog log
  ) {
    var rollbackType = inputEvent.eventType();
    Authorisation paymentData = log.one(PaymentRequest).t1();
    PaymentEvent.Merchant merchant = log.one(PaymentRequest).t2();
    AcquirerResponse acquirerResponse = log.lastIfExists(AuthorisationApproved).orElse(null);
    return new AuthorisationReversalData(
            false, //rollbackType == Cancel || rollbackType == RollbackRequest,
            false, //rollbackType != Cancel,
            merchant,
            paymentData.amount().requested(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            //acquirerBatchNumber.number(), //acquirerResponse != null ? acquirerResponse.batchNumber() : 1,
            paymentData.simulation()
        );
  }

}
