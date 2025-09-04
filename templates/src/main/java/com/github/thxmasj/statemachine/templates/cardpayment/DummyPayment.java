package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Authentication;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;

public class DummyPayment extends AbstractPayment{

  public DummyPayment(AbstractSettlement settlement) {
    super(settlement);
  }

  @Override
  protected Authentication authentication() {
    return new OutgoingRequests.Authentication() {};
  }

  @Override
  protected OutgoingRequests.Preauthorisation preauthorisation() {
    return new OutgoingRequests.Preauthorisation() {};
  }

  @Override
  protected OutgoingRequests.PreauthorisationReversal preauthorisationReversal() {
    return new OutgoingRequests.PreauthorisationReversal() {};
  }

  @Override
  protected Authorisation authorisation() {
    return new OutgoingRequests.Authorisation() {};
  }

  @Override
  protected OutgoingRequests.AuthorisationReversal authorisationReversal() {
    return new OutgoingRequests.AuthorisationReversal() {};
  }

  @Override
  protected OutgoingRequests.RolledBackPreauthorisationRequest rolledBackPreauthorisationRequest() {
    return new OutgoingRequests.RolledBackPreauthorisationRequest() {};
  }

  @Override
  protected OutgoingRequests.RolledBackAuthorisationRequest rolledBackAuthorisationRequest() {
    return new OutgoingRequests.RolledBackAuthorisationRequest() {};
  }

  @Override
  protected OutgoingRequests.FailedAuthentication failedAuthentication() {
    return new OutgoingRequests.FailedAuthentication() {};
  }

  @Override
  protected OutgoingRequests.FailedTokenValidation failedTokenValidation() {
    return new OutgoingRequests.FailedTokenValidation() {};
  }

  @Override
  protected OutgoingRequests.FailedAuthorisation failedAuthorisation() {
    return new OutgoingRequests.FailedAuthorisation() {};
  }

  @Override
  protected OutgoingRequests.DeclinedAuthorisation declinedAuthorisation() {
    return new OutgoingRequests.DeclinedAuthorisation() {};
  }

  @Override
  protected OutgoingRequests.ApprovedPreauthorisation approvedPreauthorisation() {
    return new OutgoingRequests.ApprovedPreauthorisation() {};
  }

  @Override
  protected OutgoingRequests.ApprovedCapture approvedCapture() {
    return new OutgoingRequests.ApprovedCapture() {};
  }

  @Override
  protected OutgoingRequests.ApprovedAuthorisation approvedAuthorisation() {
    return new OutgoingRequests.ApprovedAuthorisation() {};
  }

  @Override
  protected OutgoingRequests.Capture capture() {
    return new OutgoingRequests.Capture() {};
  }

  @Override
  protected OutgoingRequests.CaptureTooLate captureRequestedTooLate() {
    return new OutgoingRequests.CaptureTooLate() {};
  }

  @Override
  protected OutgoingRequests.RefundAuthorisation refundAuthorisation() {
    return new OutgoingRequests.RefundAuthorisation() {};
  }

  @Override
  protected OutgoingRequests.RefundReversal refundReversal() {
    return new OutgoingRequests.RefundReversal() {};
  }

  @Override
  protected OutgoingRequests.FailedRefund failedRefund() {
    return new OutgoingRequests.FailedRefund() {};
  }

  @Override
  protected OutgoingRequests.ApprovedRefund approvedRefund() {
    return new OutgoingRequests.ApprovedRefund() {};
  }

  @Override
  protected OutgoingRequests.DeclinedRefund declinedRefund() {
    return new OutgoingRequests.DeclinedRefund() {};
  }

  @Override
  protected IncomingResponseValidator<AuthenticationResult> validateAuthenticationResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validatePreauthorisationResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validatePreauthorisationReversalResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateAuthorisationResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateAuthorisationReversalResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateAuthorisationAdviceResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateCaptureResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateRefundResponse() {
    return null;
  }

  @Override
  protected IncomingResponseValidator<AcquirerResponse> validateRefundReversalResponse() {
    return null;
  }

}
