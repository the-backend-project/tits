package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import reactor.core.publisher.Mono;

public class RefundReversalDataCreator implements DataCreator<Long, RefundReversalData> {

  public record RefundReversalData(
      HttpRequestMessage originalRequest,
      boolean clientOriginated,
      boolean technicalReversal,
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      String authorisationCode,
      String simulation
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        outgoingRequest(Acquirer, RefundRequest, Refund.class)
    );
  }

  @Override
  public Mono<RefundReversalData> execute(InputEvent<Long> inputEvent, EventLog eventLog, Input input) {
    Authorisation paymentData = eventLog.one(PaymentRequest).getUnmarshalledData();
    Refund refundData = eventLog.last(RefundRequest).getUnmarshalledData();
    AcquirerResponse acquirerResponse = eventLog.lastIfExists(RefundApproved)
        .map(Event::getUnmarshalledData)
        .orElse(null);
    return input.outgoingRequest(Acquirer, RefundRequest, String.class)
        .map(originalRequest -> new RefundReversalData(
            originalRequest.httpMessage(),
            inputEvent.eventType() == Cancel || inputEvent.eventType() == RollbackRequest,
            inputEvent.eventType() != Cancel,
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            refundData.amount(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            paymentData.simulation()
        ));
  }

}
