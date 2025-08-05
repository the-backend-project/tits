package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.secondaryId;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidAuthenticationToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidPaymentTokenOwnership;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.InvalidPaymentTokenStatus;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Type.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AuthorisationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Authorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Expired;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ExpiredAfterCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Preauthorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Authenticator;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Merchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebit;

import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.ScheduledEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public abstract class AbstractPayment implements EntityModel {

  private final AbstractSettlement settlement;
  private final UUID id = UUID.fromString("c56dc62e-24e8-43a7-868f-b87720327ff5");

  public AbstractPayment(AbstractSettlement settlement) {
    this.settlement = settlement;
  }

  @Override
  public String name() {
    return "Payment";
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  public List<OutboxQueue> queues() {
    return List.of(Queues.values());
  }

  @Override
  public State initialState() {
    return Begin;
  }

  protected abstract OutgoingRequests.Authentication authentication();

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

  protected abstract IncomingResponseValidator<AuthenticationResult> validateAuthenticationResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validatePreauthorisationResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validatePreauthorisationReversalResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationReversalResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateAuthorisationAdviceResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateCaptureResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateRefundResponse();

  protected abstract IncomingResponseValidator<AcquirerResponse> validateRefundReversalResponse();

  @SafeVarargs
  private static List<TransitionModel<?, ?>> join(List<TransitionModel<?, ?>>...lists) {
    if (lists.length == 0) return List.of();
    var stream = lists[0].stream();
    for (int i = 1; i < lists.length; i++) {
      stream = Stream.concat(stream, lists[i].stream());
    }
    return stream.toList();
  }

  @Override
  public List<TransitionModel<?, ?>> transitions() {
    return join(
        List.of(
            from(Begin).to(Begin).onEvent(RollbackRequest).response(new Created()),
            from(Begin).to(ProcessingAuthentication)
                .onEvent(PaymentRequest)
                .withData(new AuthenticationDataCreator())
                //.store(authorisation -> authorisation)
                .notify(request(authentication()).to(Authenticator).responseValidator(validateAuthenticationResponse())),
            from(ProcessingAuthentication).to(Begin)
                .onEvent(InvalidAuthenticationToken)
                .response(new BadRequest()),
            from(ProcessingAuthentication).to(Begin)
                .onEvent(InvalidPaymentTokenStatus)
                .response("Payment token is inactive", new BadRequest()),
            from(ProcessingAuthentication).to(Begin)
                .onEvent(AuthenticationFailed)
                .withData(new AuthenticationFailedDataCreator())
                //.store(authenticationResult -> authenticationResult)
                .notify(request(failedAuthentication()).to(Acquirer).guaranteed())
                .response(_ -> "", new BadRequest()),
            from(ProcessingAuthentication).to(Begin)
                .onEvent(InvalidPaymentTokenOwnership)
                .withData(new AuthenticationFailedDataCreator())
                .notify(request(failedTokenValidation()).to(Acquirer)
                    .guaranteed()
                    .responseValidator(validateAuthorisationAdviceResponse()))
                .response(_ -> "", new BadRequest()),
            from(ProcessingAuthentication).toSelf().onEvent(RollbackRequest).response(new Created()),
            from(ProcessingAuthentication).to(ProcessingAuthorisation)
                .onEvent(PreauthorisationRequest)
                .withData(new AuthorisationRequestDataCreator())
                .notify(request(preauthorisation()).to(Acquirer).responseValidator(validatePreauthorisationResponse()))
                .response(_ -> "", new Created())
                .reverse(transition -> transition.withData(new PreauthorisationReversalDataCreator())
                    .notify(request(preauthorisationReversal()).to(Acquirer)
                        .guaranteed()
                        .responseValidator(validatePreauthorisationReversalResponse()))
                    .notify(request(rolledBackPreauthorisationRequest()).to(Merchant).guaranteed())),
            from(ProcessingAuthentication).to(ProcessingAuthorisation)
                .onEvent(AuthorisationRequest)
                .withData(new AuthorisationRequestDataCreator())
                .filter(d -> d.t1().capture()).orElse(PreauthorisationRequest, Tuple2::t2)
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.t1().merchant().id()).last()))
                .notify(request(authorisation()).to(Acquirer).responseValidator(validateAuthorisationResponse()))
                .response(_ -> "", new Created())
                .reverse(builder -> builder.withData(new AuthorisationReversalDataCreator())
                    .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchantId()).last().createIfNotExists()))
                    .notify(request(authorisationReversal()).to(Acquirer)
                        .guaranteed()
                        .responseValidator(validateAuthorisationReversalResponse()))
                    .notify(request(rolledBackAuthorisationRequest()).to(Merchant).guaranteed())),
            from(ProcessingAuthorisation).to(Preauthorised)
                .onEvent(PreauthorisationApproved)
                .withData(new AuthorisationResponseDataCreator())
                .notify(request(approvedPreauthorisation()).to(Merchant).guaranteed())
                .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7)))),
            from(ProcessingAuthorisation).toSelf().onEvent(Rollback).build(),
            from(ProcessingAuthorisation).toSelf().onEvent(RollbackRequest).response(new Created()),
            from(ProcessingAuthorisation).to(AuthorisationFailed)
                .onEvent(RequestUndelivered)
                .withData(new AuthorisationRequestUndeliveredData())
                .notify(request(failedAuthorisation()).to(Merchant).guaranteed()),
            from(ProcessingAuthorisation).to(Authorised)
                .onEvent(AuthorisationApproved)
                .withData(new AuthorisationResponseDataCreator())
                .notify(request(approvedAuthorisation()).to(Merchant).guaranteed())
                .trigger(data -> event(MerchantCredit, data.authorisation().amount().requested()).onEntity(settlement)
                    .identifiedBy(secondaryId(
                        AcquirerBatchNumber,
                        new AcquirerBatchNumber(
                            data.authorisation().merchant().id(),
                            data.acquirerResponse().batchNumber()
                        )
                    ).createIfNotExists())
                    .and(model(BatchNumber).group(data.authorisation().merchant().id()).last().createIfNotExists()))
                .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7))))
                .reverse(builder -> builder.withData(new AuthorisationReversalDataCreator())
                    .trigger(data -> event(SettlementEvent.Type.MerchantCreditReversed, data.amount()).onEntity(
                            settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchantId()).last()))),
            from(ProcessingAuthorisation).to(AuthorisationFailed)
                .onEvent(AcquirerDeclined)
                .withData(new  AuthorisationResponseDataCreator())
                .notify(request(declinedAuthorisation()).to(Merchant).guaranteed()),
            from(ProcessingCapture).to(Authorised)
                .onEvent(CaptureApproved)
                .withData(new ApprovedCaptureDataCreator())
                .notify(request(approvedCapture()).to(Merchant).guaranteed())
                .trigger(data -> event(MerchantCredit, data.amount()).onEntity(settlement).identifiedBy(secondaryId(
                    AcquirerBatchNumber,
                    new AcquirerBatchNumber(data.merchantId(), data.netsSessionNumber())
                ).createIfNotExists()).and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists())),
            from(Preauthorised).to(Preauthorised).onEvent(Rollback).build(),
            from(Preauthorised).to(Expired).onEvent(AuthorisationExpired).build(),
            from(Preauthorised).to(Preauthorised).onEvent(RollbackRequest).response(new Created()),
            from(Preauthorised).to(Preauthorised).onEvent(Cancel).response(new Created()),
            from(Authorised).toSelf().onEvent(Rollback).build(),
            from(Authorised).toSelf().onEvent(RollbackRequest).response(new Created()),
            from(Authorised).to(ExpiredAfterCapture).onEvent(AuthorisationExpired).build(),
            captureRequestTransition(Preauthorised),
            captureRequestTransition(Authorised),
            captureRequestedTooLateTransition(Expired),
            captureRequestedTooLateTransition(ExpiredAfterCapture)
        ),
        refundTransitions(Authorised, 1),
        refundTransitions(ExpiredAfterCapture, 2)
    );
  }

  private TransitionModel<Capture, CaptureRequestData> captureRequestTransition(State anchor) {
    return from(anchor).to(ProcessingCapture)
        .onEvent(CaptureRequest)
        .withData(new CaptureRequestDataCreator())
        .filter(d -> d.alreadyCapturedAmount() + d.captureData().amount() <= d.authorisationData().amount().requested())
        .orElseInvalidRequest("Capture amount too large")
        //.store(data -> data.captureData()) // default: a) input or b) data or c) null? Now: a
        .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
            .identifiedBy(model(BatchNumber).group(data.authorisationData().merchant().id()).last()))
        .response(_ -> "", new Created())
        .notify(request(capture()).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse()));
  }

  private TransitionModel<?, ?> captureRequestedTooLateTransition(State anchor) {
    return from(anchor).toSelf()
        .onEvent(CaptureRequest)
        .withData(new CaptureRequestedTooLateDataCreator())
        //.store(data -> data.captureData()) // same as input
        .notify(request(captureRequestedTooLate()).to(Acquirer).guaranteed())
        .response(_ -> "", new BadRequest());
  }

  private List<TransitionModel<?, ?>> refundTransitions(State anchor, int i) {
    State processingState = new State() {
      @Override
      public String name() {
        return "ProcessingRefund" + i;
      }
      @Override
      public Optional<Timeout> timeout() {
        return Optional.of(new Timeout(Duration.ofMillis(6600), Rollback));
      }
      @Override
      public boolean equals(Object o) {
        return o instanceof State os && os.name().equals(name());
      }
    };
    return List.of(
        from(anchor).to(processingState)
            .onEvent(RefundRequest)
            .withData(new RefundRequestDataCreator())
            .filter(d -> d.alreadyRefundedAmount() + d.refundData().amount() <= d.alreadyCapturedAmount())
            .orElseInvalidRequest("Refund amount too large")
            .response(_ -> "", new Created())
            .notify(request(refundAuthorisation()).to(Acquirer).responseValidator(validateRefundResponse()))
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId()).last()))
                .notify(request(refundReversal()).to(Acquirer)
                    .guaranteed()
                    .responseValidator(validateRefundReversalResponse()))),
        from(processingState).toSelf().onEvent(Rollback).build(),
        from(processingState).toSelf().onEvent(RollbackRequest).response(new Created()),
        from(processingState).to(anchor)
            .onEvent(RequestUndelivered)
            .withData(new FailedRefundDataCreator())
            .notify(request(failedRefund()).to(Merchant).guaranteed()),
        from(processingState).to(anchor)
            .onEvent(RefundApproved)
            .withData(new ApprovedRefundDataCreator())
            .notify(request(approvedRefund()).to(Merchant).guaranteed())
            .trigger(data -> event(MerchantDebit, data.amount()).onEntity(settlement).identifiedBy(secondaryId(
                AcquirerBatchNumber,
                new AcquirerBatchNumber(data.merchantId(), data.bankBatchNumber())
            ).createIfNotExists()).and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists()))
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(SettlementEvent.Type.MerchantDebitReversed, data.amount()).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId()).last()))),
        from(processingState).to(anchor)
            .onEvent(AcquirerDeclined)
            .withData(new DeclinedRefundDataCreator())
            .notify(request(declinedRefund()).to(Merchant).guaranteed())
    );
  }

  @Override
  public EntityModel parentEntity() {
    return settlement;
  }

}
