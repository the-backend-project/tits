package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationResponseDataCreator.AuthorisationResponseData;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationRequestDataCreator.AuthorisationRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestedTooLateDataCreator.CaptureRequestedTooLateData;
import com.github.thxmasj.statemachine.templates.cardpayment.DeclinedRefundDataCreator.DeclinedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.FailedRefundDataCreator.FailedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Authentication;
import com.github.thxmasj.statemachine.templates.cardpayment.OutgoingRequests.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import reactor.core.publisher.Mono;

public class DummyPayment extends AbstractPayment{

  public DummyPayment(AbstractSettlement settlement) {
    super(settlement);
  }

  @Override
  protected Authentication authentication() {
    return new OutgoingRequests.Authentication() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthenticationData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "Authentication";
      }
    };
  }

  @Override
  protected OutgoingRequests.Preauthorisation preauthorisation() {
    return new OutgoingRequests.Preauthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationRequestData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "Preauthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.PreauthorisationReversal preauthorisationReversal() {
    return new OutgoingRequests.PreauthorisationReversal() {
      @Override
      public Mono<HttpRequestMessage> create(
          PreauthorisationReversalData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "PreauthorisationReversal";
      }        };
  }

  @Override
  protected Authorisation authorisation() {
    return new OutgoingRequests.Authorisation() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationRequestData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "Authorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.AuthorisationReversal authorisationReversal() {
    return new OutgoingRequests.AuthorisationReversal() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationReversalData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }


      @Override
      public String toString() {
        return "AuthorisationReversal";
      }
    };
  }

  @Override
  protected OutgoingRequests.RolledBackPreauthorisationRequest rolledBackPreauthorisationRequest() {
    return new OutgoingRequests.RolledBackPreauthorisationRequest() {
      @Override
      public Mono<HttpRequestMessage> create(
          PreauthorisationReversalData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "RolledBackPreauthorisationRequest";
      }
    };
  }

  @Override
  protected OutgoingRequests.RolledBackAuthorisationRequest rolledBackAuthorisationRequest() {
    return new OutgoingRequests.RolledBackAuthorisationRequest() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationReversalData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "RolledBackAuthorisationRequest";
      }
    };
  }

  @Override
  protected OutgoingRequests.FailedAuthentication failedAuthentication() {
    return new OutgoingRequests.FailedAuthentication() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationRequestData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "FailedAuthentication";
      }

    };
  }

  @Override
  protected OutgoingRequests.FailedTokenValidation failedTokenValidation() {
    return new OutgoingRequests.FailedTokenValidation() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationRequestData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "FailedTokenValidation";
      }
    };
  }

  @Override
  protected OutgoingRequests.FailedAuthorisation failedAuthorisation() {
    return new OutgoingRequests.FailedAuthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationRequestData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "FailedAuthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.DeclinedAuthorisation declinedAuthorisation() {
    return new OutgoingRequests.DeclinedAuthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(AuthorisationResponseData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "DeclinedAuthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.ApprovedPreauthorisation approvedPreauthorisation() {
    return new OutgoingRequests.ApprovedPreauthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(AuthorisationResponseData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "ApprovedPreauthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.ApprovedCapture approvedCapture() {
    return new OutgoingRequests.ApprovedCapture() {
      @Override
      public Mono<HttpRequestMessage> create(ApprovedCaptureData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "ApprovedCapture";
      }
    };
  }

  @Override
  protected OutgoingRequests.ApprovedAuthorisation approvedAuthorisation() {
    return new OutgoingRequests.ApprovedAuthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(
          AuthorisationResponseData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "ApprovedAuthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.Capture capture() {
    return new OutgoingRequests.Capture() {
      @Override
      public Mono<HttpRequestMessage> create(CaptureRequestData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "Capture";
      }
    };
  }

  @Override
  protected OutgoingRequests.CaptureTooLate captureRequestedTooLate() {
    return new OutgoingRequests.CaptureTooLate() {
      @Override
      public Mono<HttpRequestMessage> create(
          CaptureRequestedTooLateData data,
          EntityId entityId,
          String correlationId,
          Input input
      ) {
        return null;
      }

      @Override
      public String toString() {
        return "CaptureTooLate";
      }
    };
  }

  @Override
  protected OutgoingRequests.RefundAuthorisation refundAuthorisation() {
    return new OutgoingRequests.RefundAuthorisation() {
      @Override
      public Mono<HttpRequestMessage> create(RefundRequestData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "RefundAuthorisation";
      }
    };
  }

  @Override
  protected OutgoingRequests.RefundReversal refundReversal() {
    return new OutgoingRequests.RefundReversal() {
      @Override
      public Mono<HttpRequestMessage> create(RefundReversalData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "RefundReversal";
      }
    };
  }

  @Override
  protected OutgoingRequests.FailedRefund failedRefund() {
    return new OutgoingRequests.FailedRefund() {
      @Override
      public Mono<HttpRequestMessage> create(FailedRefundData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "FailedRefund";
      }
    };
  }

  @Override
  protected OutgoingRequests.ApprovedRefund approvedRefund() {
    return new OutgoingRequests.ApprovedRefund() {
      @Override
      public Mono<HttpRequestMessage> create(ApprovedRefundData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "ApprovedRefund";
      }
    };
  }

  @Override
  protected OutgoingRequests.DeclinedRefund declinedRefund() {
    return new OutgoingRequests.DeclinedRefund() {
      @Override
      public Mono<HttpRequestMessage> create(DeclinedRefundData data, EntityId entityId, String correlationId, Input input) {
        return null;
      }

      @Override
      public String toString() {
        return "DeclinedRefund";
      }
    };
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
