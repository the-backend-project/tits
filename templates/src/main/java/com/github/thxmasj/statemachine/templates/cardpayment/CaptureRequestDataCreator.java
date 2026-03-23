package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;

public class CaptureRequestDataCreator {

  public record CaptureRequestData(
      Authorisation authorisationData,
      PaymentEvent.Merchant merchant,
      AuthenticationResult authenticationResult,
      AcquirerResponse bankResponse,
      Capture captureData,
      long alreadyCapturedAmount,
      EntityId entityId
  ) {}

}
