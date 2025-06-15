package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedAuthorisationDataCreator.ApprovedAuthorisationData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Amount;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class ApprovedAuthorisationDataCreator implements DataCreator<ApprovedAuthorisationData> {

  public record ApprovedAuthorisationData(
      String merchantId,
      String merchantAggregatorId,
      Amount amount,
      String merchantReference,
      int acquirerBatchNumber,
      String stan,
      String authorisationCode,
      String responseCode
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest, AuthorisationRequest),
        current(AuthorisationApproved)
    );
  }

  @Override
  public Mono<ApprovedAuthorisationData> execute(Input input) {
    var paymentData = input.one(PreauthorisationRequest, AuthorisationRequest).getUnmarshalledData(Authorisation.class);
    var bankResponse = input.current(AuthorisationApproved).getUnmarshalledData(AcquirerResponse.class);
    return Mono.just(new ApprovedAuthorisationData(
        paymentData.merchant().id(),
        paymentData.merchant().aggregatorId(),
        paymentData.amount(),
        paymentData.merchantReference(),
        bankResponse.batchNumber(),
        bankResponse.stan(),
        bankResponse.authorisationCode(),
        bankResponse.responseCode()
    ));
  }

}
