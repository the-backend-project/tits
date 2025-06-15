package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.id;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.OutgoingRequestModel.Builder.request;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AcquirerDeclined;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationExpired;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.CaptureRequestedTooLate;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidPaymentToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AuthorisationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Authorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.PendingAuthorisationResponse;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.PendingCaptureResponse;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.PendingRefundResponse;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Preauthorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.Subscribers.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Subscribers.Merchant;

import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.ScheduledEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

public abstract class AbstractPayment implements EntityModel {

  private final AbstractSettlement settlement;

  public AbstractPayment(AbstractSettlement settlement) {this.settlement = settlement;}

  @Override
  public String name() {
    return "Payment";
  }

  @Override
  public List<EventType> eventTypes() {
    return List.of(PaymentEvent.Type.values());
  }

  @Override
  public List<Subscriber> subscribers() {
    return List.of(Subscribers.values());
  }

  @Override
  public State initialState() {
    return Begin;
  }

  protected abstract OutgoingRequests.Preauthorisation preauthorisation();

  protected abstract OutgoingRequests.PreauthorisationReversal preauthorisationReversal();

  protected abstract OutgoingRequests.Authorisation authorisation();

  protected abstract OutgoingRequests.AuthorisationReversal authorisationReversal();

  protected abstract OutgoingRequests.RolledBackPreauthorisationRequest rolledBackPreauthorisationRequest();


  protected abstract OutgoingRequests.RolledBackAuthorisationRequest rolledBackAuthorisationRequest();

  protected abstract OutgoingRequests.FailedAuthentication failedAuthentication();

  protected abstract OutgoingRequests.FailedTokenValidation failedTokenValidation();

  protected abstract OutgoingRequests.FailedAuthorisation failedAuthorisation();

  protected abstract OutgoingRequests.DeclinedAuthorisation declinedAuthorisation();

  protected abstract OutgoingRequests.ApprovedPreauthorisation approvedPreauthorisation();

  protected abstract OutgoingRequests.ApprovedCapture approvedCapture();

  protected abstract OutgoingRequests.ApprovedAuthorisation approvedAuthorisation();

  protected abstract OutgoingRequests.Capture capture();

  protected abstract OutgoingRequests.CaptureTooLate captureRequestedTooLate();

  protected abstract OutgoingRequests.RefundAuthorisation refundAuthorisation();

  protected abstract OutgoingRequests.RefundReversal refundReversal();

  protected abstract OutgoingRequests.FailedRefund failedRefund();

  protected abstract OutgoingRequests.ApprovedRefund approvedRefund();

  protected abstract OutgoingRequests.DeclinedRefund declinedRefund();

  protected abstract IncomingResponseValidator<AcquirerResponse> validatePreauthorisationResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validatePreauthorisationReversalResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationReversalResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationAdviceResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateCaptureResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateRefundResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateRefundReversalResponse();

  @Override
  public List<TransitionModel<?>> transitions() {
    return List.of(
        from(Begin).to(Begin).onEvent(RollbackRequest)
            .response(new Created()),
        from(Begin).to(PendingAuthorisationResponse).onEvent(PreauthorisationRequest)
            .withData(new AuthorisationData())
            .notify(request(preauthorisation()).to(Acquirer).responseValidator(validatePreauthorisationResponse()))
            .response(_ -> "", new Created())
            .reverse(transition -> transition
                .withData(new PreauthorisationReversalDataCreator())
                .notify(request(preauthorisationReversal()).to(Acquirer).guaranteed().responseValidator(validatePreauthorisationReversalResponse()))
                .notify(request(rolledBackPreauthorisationRequest()).to(Merchant).guaranteed())
            ),
        from(Begin).to(PendingAuthorisationResponse).onEvent(AuthorisationRequest)
            .withData(new AuthorisationData())
            .trigger(data -> event(BuiltinEventTypes.Status)
                .onEntity(settlement)
                .identifiedBy(model(BatchNumber).group(data.merchant().id()).last())
            )
            .notify(request(authorisation()).to(Acquirer).responseValidator(validateAuthorisationResponse()))
            .response(_ -> "", new Created())
            .reverse(builder -> builder.withData(new AuthorisationReversalDataCreator())
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId())
                        .last()
                        .createIfNotExists())
                )
                .notify(request(authorisationReversal()).to(Acquirer).guaranteed().responseValidator(validateAuthorisationReversalResponse()))
                .notify(request(rolledBackAuthorisationRequest()).to(Merchant).guaranteed())
            ),
        from(Begin).to(Begin).onEvent(AuthenticationFailed)
            .withData(new AuthorisationData())
            .notify(request(failedAuthentication()).to(Acquirer).guaranteed())
            .response(_ -> "", new BadRequest()),
        from(Begin).to(Begin).onEvent(InvalidPaymentToken)
            .withData(new AuthorisationData())
            .notify(request(failedTokenValidation()).to(Acquirer).guaranteed().responseValidator(validateAuthorisationAdviceResponse()))
            .response(_ -> "", new BadRequest()),
        from(PendingAuthorisationResponse).to(Preauthorised).onEvent(PreauthorisationApproved)
            .withData(_ -> Mono.just(""))
            .notify(request(approvedPreauthorisation()).to(Merchant).guaranteed())
            .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7)))),
        from(PendingAuthorisationResponse).to(PendingAuthorisationResponse).onEvent(Rollback).build(),
        from(PendingAuthorisationResponse).to(PendingAuthorisationResponse).onEvent(RollbackRequest)
            .response(new Created()),
        from(PendingAuthorisationResponse).to(AuthorisationFailed).onEvent(RequestUndelivered)
            .withData(new AuthorisationRequestUndeliveredData())
            .notify(request(failedAuthorisation()).to(Merchant).guaranteed()),
        from(PendingAuthorisationResponse).to(Authorised).onEvent(AuthorisationApproved)
            .withData(new ApprovedAuthorisationDataCreator())
            .notify(request(approvedAuthorisation()).to(Merchant).guaranteed())
            .trigger(data ->
                event(MerchantCredit, data.amount().requested())
                    .onEntity(settlement)
                    .identifiedBy(id(
                        AcquirerBatchNumber,
                        new AcquirerBatchNumber(data.merchantId(), data.acquirerBatchNumber())
                    ).createIfNotExists())
                    .and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists())
            )
            .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7))))
            .reverse(builder -> builder.withData(new AuthorisationReversalDataCreator())
                .trigger(data ->
                    event(
                        SettlementEvent.Type.MerchantCreditReversed,
                        data.amount()
                    ).onEntity(settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchantId()).last())
                )
            ),
        from(PendingAuthorisationResponse).to(AuthorisationFailed).onEvent(AcquirerDeclined)
            .withData(_ -> Mono.just(""))
            .notify(request(declinedAuthorisation()).to(Merchant).guaranteed()),
        from(PendingCaptureResponse).to(Authorised).onEvent(CaptureApproved)
            .withData(new ApprovedCaptureDataCreator())
            .notify(request(approvedCapture()).to(Merchant).guaranteed())
            .trigger(data ->
                event(MerchantCredit, data.amount())
                    .onEntity(settlement)
                    .identifiedBy(id(
                        AcquirerBatchNumber,
                        new AcquirerBatchNumber(data.merchantId(), data.netsSessionNumber())
                    ).createIfNotExists())
                    .and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists())
            ),
        from(Preauthorised).to(Preauthorised).onEvent(Rollback).build(),
        from(Preauthorised).to(Preauthorised).onEvent(AuthorisationExpired).build(),
        from(Preauthorised).to(Preauthorised).onEvent(RollbackRequest).response(new Created()),
        from(Preauthorised).to(Preauthorised).onEvent(Cancel).response(new Created()),
        from(Preauthorised).to(PendingCaptureResponse).onEvent(CaptureRequest)
            .withData(new CaptureRequestDataCreator())
            .trigger(data ->
                event(BuiltinEventTypes.Status)
                    .onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.paymentData().merchant().id()).last())
            )
            .response(_ -> "", new Created())
            .notify(request(capture()).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse())),
        from(Authorised).to(PendingCaptureResponse).onEvent(CaptureRequest)
            .withData(new CaptureRequestDataCreator())
            .trigger(data ->
                event(BuiltinEventTypes.Status)
                    .onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.paymentData().merchant().id()).last())
            )
            .response(_ -> "", new Created())
            .notify(request(capture()).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse())),
        from(Preauthorised).to(Preauthorised).onEvent(CaptureRequestedTooLate)
            .withData(new CaptureRequestedTooLateDataCreator())
            .notify(request(captureRequestedTooLate()).to(Acquirer).guaranteed())
            .response(_ -> "", new BadRequest()),
        from(Authorised).to(Authorised).onEvent(Rollback).build(),
        from(Authorised).to(Authorised).onEvent(AuthorisationExpired).build(),
        from(Authorised).to(Authorised).onEvent(CaptureRequestedTooLate)
            .withData(new CaptureRequestedTooLateDataCreator())
            .notify(request(captureRequestedTooLate()).to(Acquirer).guaranteed())
            .response(_ -> "", new BadRequest()),
        from(Authorised).to(Authorised).onEvent(RollbackRequest).response(new Created()),
        from(Authorised).to(PendingRefundResponse).onEvent(RefundRequest)
            .withData(new RefundRequestDataCreator())
            .response(_ -> "", new Created())
            .notify(request(refundAuthorisation()).to(Acquirer)
                .responseValidator(validateRefundResponse()))
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(
                        settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId()).last())
                )
                .notify(request(refundReversal()).to(Acquirer).guaranteed().responseValidator(validateRefundReversalResponse()))
            ),
        from(PendingRefundResponse).to(PendingRefundResponse).onEvent(Rollback).build(),
        from(PendingRefundResponse).to(PendingRefundResponse).onEvent(RollbackRequest).response(new Created()),
        from(PendingRefundResponse).to(Authorised).onEvent(RequestUndelivered)
            .withData(new FailedRefundDataCreator())
            .notify(request(failedRefund()).to(Merchant).guaranteed()),
        from(PendingRefundResponse).to(Authorised).onEvent(RefundApproved)
            .withData(new ApprovedRefundDataCreator())
            .notify(request(approvedRefund()).to(Merchant).guaranteed())
            .trigger(data -> event(MerchantDebit, data.amount())
                .onEntity(settlement)
                .identifiedBy(id(
                    AcquirerBatchNumber,
                    new AcquirerBatchNumber(data.merchantId(), data.bankBatchNumber())
                )
                    .createIfNotExists())
                .and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists())
            )
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(
                        SettlementEvent.Type.MerchantDebitReversed,
                        data.amount()
                    )
                        .onEntity(settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchantId()).last())
                )
            ),
        from(PendingRefundResponse).to(Authorised).onEvent(AcquirerDeclined)
            .withData(new DeclinedRefundDataCreator())
            .notify(request(declinedRefund()).to(Merchant).guaranteed())
    );
  }

  @Override
  public EntityModel parentEntity() {
    return settlement;
  }

}
