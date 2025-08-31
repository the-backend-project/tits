package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import reactor.core.publisher.Mono;

public class PreauthorisationReversalDataCreator implements DataCreator<Void, PreauthorisationReversalData> {

  public record PreauthorisationReversalData(
      boolean clientOriginated,
      boolean technicalReversal,
      String merchantId,
      String merchantAggregatorId,
      Amount amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

  @Override
  public Mono<PreauthorisationReversalData> execute(InputEvent<Void> inputEvent, EventLog eventLog) {
    Authorisation paymentData = eventLog.one(PaymentRequest).getUnmarshalledData();
    AcquirerResponse acquirerResponse = eventLog.lastIfExists(PreauthorisationApproved)
        .map(Event::getUnmarshalledData).orElse(null);
    return Mono.just(new PreauthorisationReversalData(
            inputEvent.eventType() == Cancel || inputEvent.eventType() == RollbackRequest,
            inputEvent.eventType() != Cancel,
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            paymentData.amount(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            paymentData.simulation()
        ));
  }

}
