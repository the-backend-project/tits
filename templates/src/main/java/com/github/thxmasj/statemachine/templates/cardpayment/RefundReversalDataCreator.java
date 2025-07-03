package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Requirements.last;
import static com.github.thxmasj.statemachine.Requirements.lastIfExists;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Requirements.outgoingRequest;
import static com.github.thxmasj.statemachine.Requirements.trigger;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.BankRequestFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.BankRespondedIncomprehensibly;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import reactor.core.publisher.Mono;

public class RefundReversalDataCreator implements DataCreator<RefundReversalData> {

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
        trigger(Cancel, Rollback, RollbackRequest, BankRequestFailed, BankRespondedIncomprehensibly),
        one(PaymentRequest),
        last(RefundRequest),
        outgoingRequest(Acquirer, RefundRequest, String.class),
        lastIfExists(RefundApproved)
    );
  }

  @Override
  public Mono<RefundReversalData> execute(Input input) {
    Authorisation paymentData = input.one(PaymentRequest).getUnmarshalledData(Authorisation.class);
    Refund refundData = input.last(RefundRequest).getUnmarshalledData(Refund.class);
    AcquirerResponse acquirerResponse = input.lastIfExists(RefundApproved)
        .map(e -> e.getUnmarshalledData(AcquirerResponse.class))
        .orElse(null);
    return input.outgoingRequest(Acquirer, RefundRequest, String.class)
        .map(originalRequest -> new RefundReversalData(
            originalRequest.httpMessage(),
            input.trigger(Cancel, Rollback, RollbackRequest, BankRequestFailed, BankRespondedIncomprehensibly).clientId() != null,
            input.trigger(Cancel, Rollback, RollbackRequest, BankRequestFailed, BankRespondedIncomprehensibly).type() != Cancel,
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            refundData.amount(),
            paymentData.merchantReference(),
            acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
            paymentData.simulation()
        ));
  }

}
