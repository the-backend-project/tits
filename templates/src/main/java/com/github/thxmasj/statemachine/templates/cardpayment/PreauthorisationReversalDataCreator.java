package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Requirements.lastIfExists;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.Requirements.trigger;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.BankRequestFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.BankRespondedIncomprehensibly;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RollbackRequest;
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

public class PreauthorisationReversalDataCreator implements DataCreator<Void, PreauthorisationReversalData> {

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
  public Mono<PreauthorisationReversalData> execute(InputEvent<Void> inputEvent, Input input) {
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
