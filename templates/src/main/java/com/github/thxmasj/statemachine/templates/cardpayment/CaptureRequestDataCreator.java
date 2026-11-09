package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import java.time.ZonedDateTime;

public class CaptureRequestDataCreator {

  public record CaptureRequestData(
      Authorisation authorisationData,
      PaymentEvent.Merchant merchant,
      AuthenticationResult authenticationResult,
      AcquirerResponse bankResponse,
      Capture captureData,
      long alreadyCapturedAmount,
      ZonedDateTime captureTime
  ) {}

}
