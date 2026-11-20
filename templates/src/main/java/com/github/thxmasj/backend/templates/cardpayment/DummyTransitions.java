package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedCapture;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedPreauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authentication;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Capture;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.CaptureRequestedTooLate;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthentication;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedRefund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Preauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Refund;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnAccepted;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnUnknown;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackAuthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackPreauthorisation;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.ApprovedCutOff;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.Reconciliation;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.http.outbox.AtLeastOnce;
import com.github.thxmasj.backend.http.outbox.AtLeastOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.AtMostOnce;
import com.github.thxmasj.backend.http.outbox.AtMostOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.AtMostOnceWithRollbackDispatcher;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import java.time.Duration;
import reactor.core.publisher.Mono;

public class DummyTransitions {

  /// The card payment template with dispatchers which create no request messages, e.g. to draw its diagrams
  public static CardPayments cardPayments() {
    return new CardPayments(
        null,
        p -> dispatcher(atMostOnce(Authentication, p)),
        p -> reversibleDispatcher(Preauthorisation, PreauthorisationRollbackOnAccepted, PreauthorisationRollbackOnUnknown, p),
        p -> reversibleDispatcher(Authorisation, AuthorisationRollbackOnAccepted, AuthorisationRollbackOnUnknown, p),
        p -> dispatcher(atLeastOnce(Capture, p)),
        p -> reversibleDispatcher(Refund, RefundRollbackOnAccepted, RefundRollbackOnUnknown, p),
        p -> dispatcher(atLeastOnce(CaptureRequestedTooLate, p)),
        p -> dispatcher(atLeastOnce(FailedAuthentication, p)),
        s -> dispatcher(atLeastOnce(Reconciliation, s)),
        p -> dispatcher(atLeastOnce(RolledBackAuthorisation, p)),
        p -> dispatcher(atLeastOnce(RolledBackPreauthorisation, p)),
        p -> dispatcher(atLeastOnce(ApprovedPreauthorisation, p)),
        p -> dispatcher(atLeastOnce(FailedAuthorisation, p)),
        p -> dispatcher(atLeastOnce(ApprovedAuthorisation, p)),
        p -> dispatcher(atLeastOnce(DeclinedAuthorisation, p)),
        p -> dispatcher(atLeastOnce(ApprovedCapture, p)),
        p -> dispatcher(atLeastOnce(FailedRefund, p)),
        p -> dispatcher(atLeastOnce(ApprovedRefund, p)),
        p -> dispatcher(atLeastOnce(DeclinedRefund, p)),
        s -> dispatcher(atLeastOnce(ApprovedCutOff, s))
    );
  }

  public record Request() {}
  public record Response() {}

  /// A dispatcher which creates no request messages, for outbox requests which are not exercised.
  public static <X> AtLeastOnceDispatcher<X, Request> dispatcher(AtLeastOnce<Request> outbox) {
    return AtLeastOnceDispatcher.of(outbox, _ -> null);
  }

  /// A dispatcher which creates no request messages, for outbox requests which are not exercised.
  public static <X> AtMostOnceDispatcher<X, Request> dispatcher(AtMostOnce<Request, Void> outbox) {
    return AtMostOnceDispatcher.of(outbox, _ -> null);
  }

  /// A dispatcher which creates no request messages, for outbox requests which are not exercised.
  public static <X, Y> AtMostOnceWithRollbackDispatcher<X, Request, Y> reversibleDispatcher(
      Identifiable id,
      Identifiable rollbackOnAcceptedId,
      Identifiable rollbackOnUnknownId,
      EntityModel processModel
  ) {
    return AtMostOnceWithRollbackDispatcher.of(atMostOnce(id, rollbackOnAcceptedId, rollbackOnUnknownId, processModel), _ -> null, (_, _) -> Mono.empty());
  }

  public static AtMostOnce<Request, Void> atMostOnce(Identifiable id, EntityModel processModel) {
    return HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .forwarder(null)
        .processModel(processModel)
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .build(id.name(), id.id());
  }

  public static AtMostOnce<Request, Request> atMostOnce(
      Identifiable id,
      Identifiable rollbackOnAcceptedId,
      Identifiable rollbackOnUnknownId,
      EntityModel processModel
  ) {
    return HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .forwarder(null)
        .processModel(processModel)
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .rollbackOnAcceptedModel(DummyTransitions.atLeastOnce(rollbackOnAcceptedId, processModel))
        .rollbackOnUnknownModel(DummyTransitions.atLeastOnce(rollbackOnUnknownId, processModel))
        .build(id.name(), id.id());
  }

  public static AtLeastOnce<Request> atLeastOnce(Identifiable id, EntityModel processModel) {
    return HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .forwarder(null)
        .processModel(processModel)
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isFailureTransient(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .isFailureByInvalidResponseTransient(_ -> true)
        .isAttemptAvailable(_ -> true)
        .backoffAlgorithm(_ -> Duration.ofSeconds(1))
        .build(id.name(), id.id());
  }

}
