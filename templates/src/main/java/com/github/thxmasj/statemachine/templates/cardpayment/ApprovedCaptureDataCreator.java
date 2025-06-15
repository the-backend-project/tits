package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.current;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import reactor.core.publisher.Mono;

public class ApprovedCaptureDataCreator implements DataCreator<ApprovedCaptureData> {

  public record ApprovedCaptureData(
      String merchantId,
      String merchantAggregatorId,
      long amount,
      String merchantReference,
      int netsSessionNumber,
      String stan,
      String authorisationCode,
      String responseCode
  ) {}

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(PreauthorisationRequest, AuthorisationRequest),
        current(CaptureApproved)
    );
  }

  @Override
  public Mono<ApprovedCaptureData> execute(Input input) {
    var paymentData = input.one(PreauthorisationRequest, AuthorisationRequest).getUnmarshalledData(Authorisation.class);
    var bankResponse = input.current(CaptureApproved).getUnmarshalledData(AcquirerResponse.class);
    return Mono.just(new ApprovedCaptureData(
            paymentData.merchant().id(),
            paymentData.merchant().aggregatorId(),
            bankResponse.amount(),
            paymentData.merchantReference(),
            bankResponse.batchNumber(),
            bankResponse.stan(),
            bankResponse.authorisationCode(),
            bankResponse.responseCode()
        ));
  }

}
