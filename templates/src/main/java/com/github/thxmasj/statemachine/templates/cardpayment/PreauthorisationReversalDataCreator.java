package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import reactor.core.publisher.Mono;

public class PreauthorisationReversalDataCreator implements DataCreator<Long, PreauthorisationReversalData> {

  public record PreauthorisationReversalData(
      HttpRequestMessage originalRequest,
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
  public Requirements requirements() {
    return Requirements.of(
        outgoingRequest(Acquirer, PreauthorisationRequest, AuthenticationResult.class)
    );
  }

  @Override
  public Mono<PreauthorisationReversalData> execute(InputEvent<Long> inputEvent, EventLog eventLog, Input input) {
    Authorisation paymentData = eventLog.one(PaymentRequest).getUnmarshalledData();
    AcquirerResponse acquirerResponse = eventLog.lastIfExists(PreauthorisationApproved)
        .map(Event::getUnmarshalledData).orElse(null);
    return input.outgoingRequest(Acquirer, PreauthorisationRequest, String.class)
        .map(originalRequest -> new PreauthorisationReversalData(
            originalRequest.httpMessage(),
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
