package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import reactor.core.publisher.Mono;

public class ApprovedRefundDataCreator implements DataCreator<ApprovedRefundData> {

    public record ApprovedRefundData(
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      int bankBatchNumber,
      String stan,
      String authorisationCode,
      String responseCode,
      String messageId
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PaymentRequest),
        last(RefundRequest),
        last(RefundApproved)
    );
  }

  @Override
  public Mono<ApprovedRefundData> execute(Input input) {
    Authorisation authorisationData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    AcquirerResponse acquirerResponse = input.last(RefundApproved).getUnmarshalledData(AcquirerResponse.class);
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
            refundData.id()
        ));
  }

}
