package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
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
        one(PreauthorisationRequest, AuthorisationRequest),
        last(RefundRequest),
        last(RefundApproved)
    );
  }

  @Override
  public Mono<ApprovedRefundData> execute(Input input) {
    Authorisation evaluatedPaymentRequest = input.one(PreauthorisationRequest, AuthorisationRequest)
        .getUnmarshalledData(Authorisation.class);
    AcquirerResponse acquirerResponse = input.last(RefundApproved).getUnmarshalledData(AcquirerResponse.class);
    Refund refundData = input.last(RefundRequest).getUnmarshalledData(Refund.class);
    return Mono.just(new ApprovedRefundData(
            evaluatedPaymentRequest.merchant().id(),
            evaluatedPaymentRequest.merchant().aggregatorId(),
            refundData.amount(),
            evaluatedPaymentRequest.merchantReference(),
            acquirerResponse.batchNumber(),
            acquirerResponse.stan(),
            acquirerResponse.authorisationCode(),
            acquirerResponse.responseCode(),
            refundData.id()
        ));
  }

}
