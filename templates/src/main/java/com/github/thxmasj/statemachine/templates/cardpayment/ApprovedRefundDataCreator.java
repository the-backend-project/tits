package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import reactor.core.publisher.Mono;

public class ApprovedRefundDataCreator implements DataCreator<AcquirerResponse, ApprovedRefundData> {

    public record ApprovedRefundData(
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      int acquirerBatchNumber,
      String stan,
      String authorisationCode,
      String responseCode,
      String correlationId
  ) {}

  @Override
  public Mono<ApprovedRefundData> execute(InputEvent<AcquirerResponse> inputEvent, EventLog eventLog, Input unused) {
    Authorisation authorisationData = eventLog.one(PaymentRequest).getUnmarshalledData();
    AcquirerResponse acquirerResponse = inputEvent.data();
    Refund refundData = eventLog.last(RefundRequest).getUnmarshalledData();
    return Mono.just(new ApprovedRefundData(
            authorisationData.merchant().id(),
            authorisationData.merchant().aggregatorId(),
            refundData.amount(),
            authorisationData.merchantReference(),
            acquirerResponse.batchNumber(),
            acquirerResponse.stan(),
            acquirerResponse.authorisationCode(),
            acquirerResponse.responseCode(),
            refundData.correlationId()
        ));
  }

}
