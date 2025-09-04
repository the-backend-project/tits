package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;

public class CaptureRequestDataCreator implements DataCreator<Capture, CaptureRequestData> {

  @Override
  public CaptureRequestData execute(InputEvent<Capture> inputEvent, EventLog eventLog) {
    return new CaptureRequestData(
        eventLog.one(PaymentRequest),
        eventLog.one(PreauthorisationRequest),
        eventLog.one(PreauthorisationApproved),
        inputEvent.data(),
        eventLog.all(CaptureApproved).stream()
            .map(AcquirerResponse::amount)
            .mapToLong(Long::longValue)
            .sum()
    );
  }

  public record CaptureRequestData(
      Authorisation authorisationData,
      AuthenticationResult authenticationResult,
      AcquirerResponse bankResponse,
      Capture captureData,
      long alreadyCapturedAmount
  ) {}

}
