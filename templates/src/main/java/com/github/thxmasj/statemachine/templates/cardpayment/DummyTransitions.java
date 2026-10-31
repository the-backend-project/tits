package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Validated.valid;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedPreauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.ApprovedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Authorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.AuthorisationRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Capture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.CaptureRequestedTooLate;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.DeclinedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.FailedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Preauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.PreauthorisationRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.Refund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnAccepted;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RefundRollbackOnUnknown;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentTransitions.OutboxRequestModelIds.RolledBackPreauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.ApprovedCutOff;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementTransitions.OutboxRequestModelIds.Reconciliation;

import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Identifiable;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import java.time.Duration;
import java.util.UUID;

public class DummyTransitions {

  public static SettlementTransitions settlement() {
    return new SettlementTransitions(
        atLeastOnce(Reconciliation),
        atLeastOnce(ApprovedCutOff)
    );
  }

  public static PaymentTransitions payment() {
    return new PaymentTransitions(
        null,
        atMostOnce(Authentication),
        atMostOnce(Preauthorisation, PreauthorisationRollbackOnAccepted, PreauthorisationRollbackOnUnknown),
        atMostOnce(Authorisation, AuthorisationRollbackOnAccepted, AuthorisationRollbackOnUnknown),
        atLeastOnce(Capture),
        atMostOnce(Refund, RefundRollbackOnAccepted, RefundRollbackOnUnknown),
        atLeastOnce(CaptureRequestedTooLate),
        atLeastOnce(FailedAuthentication),
        atLeastOnce(RolledBackAuthorisation),
        atLeastOnce(RolledBackPreauthorisation),
        atLeastOnce(ApprovedPreauthorisation),
        atLeastOnce(FailedAuthorisation),
        atLeastOnce(ApprovedAuthorisation),
        atLeastOnce(DeclinedAuthorisation),
        atLeastOnce(ApprovedCapture),
        atLeastOnce(FailedRefund),
        atLeastOnce(ApprovedRefund),
        atLeastOnce(DeclinedRefund)
    );
  }

  public record Request() {}
  public record Response() {}

  public static <I> AtMostOnce<I, Request, Void> atMostOnce(Identifiable id) {
    return HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .build(id.name(), id.id());
  }

  public static <I, RI> AtMostOnce<I, Request, RI> atMostOnce(Identifiable id, Identifiable rollbackOnAcceptedId, Identifiable rollbackOnUnknownId) {
    return HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
        .contentParser(_ -> valid(null))
        .isAccepted(_ -> true)
        .isRejected(_ -> true)
        .isRejectedByInvalidResponse(_ -> true)
        .<RI>rollbackOnAcceptedModel(DummyTransitions.atLeastOnce(rollbackOnAcceptedId))
        .rollbackOnUnknownModel(DummyTransitions.atLeastOnce(rollbackOnUnknownId))
        .build(id.name(), id.id());
  }

  public static <I> AtLeastOnce<I> atLeastOnce(Identifiable id) {
    return HttpOutboxRequest.atLeastOnce()
        .requestPayloadType(DataType.json(Request.class))
        .responsePayloadType(DataType.json(Response.class))
        .<I>messageCreator(_ -> null)
        .forwarder(null)
        .processModel(EntityModel.of("Process", UUID.randomUUID()))
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
