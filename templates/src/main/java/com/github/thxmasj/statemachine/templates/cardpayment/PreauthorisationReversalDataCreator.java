package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.lastIfExists;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
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
        outgoingRequest(Acquirer, PreauthorisationRequest, String.class),
        one(PaymentRequest),
        lastIfExists(PreauthorisationApproved)
    );
  }

  @Override
  public Mono<PreauthorisationReversalData> execute(InputEvent<Long> inputEvent, Input input) {
    Authorisation paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    AcquirerResponse acquirerResponse = input.lastIfExists(PreauthorisationApproved)
        .map(e -> e.getUnmarshalledData(AcquirerResponse.class)).orElse(null);
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
