package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.lastIfExists;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
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
        one(PaymentRequest),
        last(RefundRequest),
        outgoingRequest(Acquirer, RefundRequest, String.class),
        lastIfExists(RefundApproved)
    );
  }

  @Override
  public Mono<RefundReversalData> execute(InputEvent<Long> inputEvent, Input input) {
    Authorisation paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    Refund refundData = input.last(RefundRequest).getUnmarshalledData(Refund.class);
    AcquirerResponse acquirerResponse = input.lastIfExists(RefundApproved)
        .map(e -> e.getUnmarshalledData(AcquirerResponse.class))
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
