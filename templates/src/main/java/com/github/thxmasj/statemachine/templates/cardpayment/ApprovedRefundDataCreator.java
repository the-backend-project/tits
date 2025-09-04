package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import reactor.core.publisher.Mono;

public class ApprovedRefundDataCreator implements DataCreator<AcquirerResponse, ApprovedRefundData> {

    public record ApprovedRefundData(
      AcquirerResponse acquirerResponse,
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      String correlationId
  ) {}

  @Override
  public Mono<ApprovedRefundData> execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog) {
    Authorisation authorisationData = eventLog.one(PaymentRequest);
    AcquirerResponse acquirerResponse = inputEvent.data();
    Refund refundData = eventLog.last(RefundRequest);
    return Mono.just(new ApprovedRefundData(
        acquirerResponse,
        authorisationData.merchant().id(),
        authorisationData.merchant().aggregatorId(),
        refundData.amount(),
        authorisationData.merchantReference(),
        refundData.correlationId()
    ));
  }

}
