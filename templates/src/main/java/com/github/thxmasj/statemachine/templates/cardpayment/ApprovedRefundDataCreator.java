package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
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
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        last(RefundRequest)
    );
  }

  @Override
  public Mono<ApprovedRefundData> execute(InputEvent<AcquirerResponse> inputEvent, Input input) {
    Authorisation authorisationData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    AcquirerResponse acquirerResponse = inputEvent.data();
    Refund refundData = input.last(RefundRequest).getUnmarshalledData(Refund.class);
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
