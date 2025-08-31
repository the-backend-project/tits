package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.DataCreator.fromEvent;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.model;
import static com.github.thxmasj.statemachine.EntitySelectorBuilder.secondaryId;
import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.OutgoingRequestModel.Builder.request;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerDeclined;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationExpired;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidAuthenticationToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenOwnership;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenStatus;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;

import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.OutboxQueue;
import com.github.thxmasj.statemachine.ScheduledEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.message.http.BadRequest;
import com.github.thxmasj.statemachine.message.http.Created;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedCaptureDataCreator.ApprovedCaptureData;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import reactor.core.publisher.Mono;
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
  private static List<TransitionModel<?, ?, ?>> join(List<TransitionModel<?, ?, ?>>...lists) {
    if (lists.length == 0) return List.of();
    var stream = lists[0].stream();
    for (int i = 1; i < lists.length; i++) {
      stream = Stream.concat(stream, lists[i].stream());
    }
    return stream.toList();
  }

  @Override
  public List<TransitionModel<?, ?, ?>> transitions() {
    return join(
        List.of(
            onEvent(RollbackRequest).from(Begin).toSelf().response(new Created()),
            onEvent(PaymentRequest).from(Begin).to(ProcessingAuthentication)
                .withData(new AuthenticationDataCreator())
                .output(Tuple2::t1)
                .notify(request((Tuple2<Authorisation, AuthenticationData> data) -> data.t2(), authentication())
                    .to(Authenticator).responseValidator(validateAuthenticationResponse())),
            onEvent(InvalidAuthenticationToken).from(ProcessingAuthentication).to(Begin)
                .response("Invalid authentication token", new BadRequest()),
            onEvent(InvalidPaymentTokenStatus).from(ProcessingAuthentication).to(Begin)
                .response("Payment token is inactive", new BadRequest()),
            onEvent(AuthenticationFailed).from(ProcessingAuthentication).to(Begin)
                .withData((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data())))
                .notify(request(failedAuthentication()).to(Acquirer).guaranteed())
                .response("Authentication failed", new BadRequest()),
            onEvent(InvalidPaymentTokenOwnership).from(ProcessingAuthentication).to(Begin)
                .withData((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data())))
                .notify(request(failedTokenValidation()).to(Acquirer)
                    .guaranteed()
                    .responseValidator(validateAuthorisationAdviceResponse()))
                .response("Payment token is not accessible", new BadRequest()),
            onEvent(RollbackRequest).from(ProcessingAuthentication).toSelf().response(new Created()),
            onEvent(PreauthorisationRequest).from(ProcessingAuthentication).to(ProcessingAuthorisation)
                .withData(((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data()))))
                .output(Tuple2::t2)
                .notify(request(preauthorisation()).to(Acquirer).responseValidator(validatePreauthorisationResponse()))
                .response("", new Created())
                .reverse(transition -> transition.withData(new PreauthorisationReversalDataCreator())
                    .notify(request(preauthorisationReversal()).to(Acquirer)
                        .guaranteed()
                        .responseValidator(validatePreauthorisationReversalResponse()))
                    .notify(request(rolledBackPreauthorisationRequest()).to(Merchant).guaranteed())),
            onEvent(AuthorisationRequest).from(ProcessingAuthentication).to(ProcessingAuthorisation)
                .withData(((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data()))))
                .filter(d -> d.t1().capture()).orElse(PreauthorisationRequest, Tuple2::t2)
                .output(Tuple2::t2)
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.t1().merchant().id()).last()))
                .notify(request(authorisation()).to(Acquirer).responseValidator(validateAuthorisationResponse()))
                .response("", new Created())
                .reverse(builder -> builder.withData(new AuthorisationReversalDataCreator())
                    .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchantId()).last().createIfNotExists()))
                    .notify(request(authorisationReversal()).to(Acquirer)
                        .guaranteed()
                        .responseValidator(validateAuthorisationReversalResponse()))
                    .notify(request(rolledBackAuthorisationRequest()).to(Merchant).guaranteed())),
            onEvent(PreauthorisationApproved).from(ProcessingAuthorisation).to(Preauthorised)
                .withData(((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data()))))
                .output(Tuple2::t2)
                .notify(request(approvedPreauthorisation()).to(Merchant).guaranteed())
                .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7)))),
            onEvent(Rollback).from(ProcessingAuthorisation).toSelf().build(),
            onEvent(RollbackRequest).from(ProcessingAuthorisation).toSelf().response(new Created()),
            onEvent(RequestUndelivered).from(ProcessingAuthorisation).to(AuthorisationFailed)
                .withData((_, log) -> Mono.just(tuple(
                    log.one(PaymentRequest).getUnmarshalledData(),
                    log.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData()
                )))
                .notify(request(failedAuthorisation()).to(Merchant).guaranteed()),
            onEvent(AuthorisationApproved).from(ProcessingAuthorisation).to(Authorised)
                .withData(((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data()))))
                .output(Tuple2::t2)
                .notify(request(approvedAuthorisation()).to(Merchant).guaranteed())
                .trigger(data -> event(MerchantCredit, data.t1().amount().requested()).onEntity(settlement)
                    .identifiedBy(secondaryId(
                        AcquirerBatchNumber,
                        new AcquirerBatchNumber(
                            data.t1().merchant().id(),
                            data.t2().batchNumber()
                        )
                    ).createIfNotExists())
                    .and(model(BatchNumber).group(data.t1().merchant().id()).last().createIfNotExists()))
                .scheduledEvents(List.of(new ScheduledEvent(AuthorisationExpired, Duration.ofDays(7))))
                .reverse(builder -> builder.withData(fromEvent(PaymentRequest))
                    .trigger(data -> event(SettlementEvent.MerchantCreditReversed, data.amount().requested()).onEntity(settlement)
                        .identifiedBy(model(BatchNumber).group(data.merchant().id()).last()))),
            onEvent(AcquirerDeclined).from(ProcessingAuthorisation).to(AuthorisationFailed)
                .withData(((input, log) -> Mono.just(tuple(log.one(PaymentRequest).getUnmarshalledData(), input.data()))))
                .notify(request(declinedAuthorisation()).to(Merchant).guaranteed()),
            onEvent(CaptureApproved).from(ProcessingCapture).to(Authorised)
                .withData(new ApprovedCaptureDataCreator())
                .output(ApprovedCaptureData::acquirerResponse)
                .notify(request(approvedCapture()).to(Merchant).guaranteed())
                .trigger(data -> event(MerchantCredit, data.acquirerResponse().amount()).onEntity(settlement).identifiedBy(secondaryId(
                    AcquirerBatchNumber,
                    new AcquirerBatchNumber(data.merchantId(), data.acquirerResponse().batchNumber())
                ).createIfNotExists()).and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists())),
            onEvent(Rollback).from(Preauthorised).to(Preauthorised).build(),
            onEvent(AuthorisationExpired).from(Preauthorised).to(Expired).build(),
            onEvent(RollbackRequest).from(Preauthorised).to(Preauthorised).response(new Created()),
            onEvent(Cancel).from(Preauthorised).to(Preauthorised).response(new Created()),
            onEvent(Rollback).from(Authorised).toSelf().build(),
            onEvent(RollbackRequest).from(Authorised).toSelf().response(new Created()),
            onEvent(AuthorisationExpired).from(Authorised).to(ExpiredAfterCapture).build(),
            captureRequestTransition(Preauthorised),
            captureRequestTransition(Authorised),
            captureRequestedTooLateTransition(Expired),
            captureRequestedTooLateTransition(ExpiredAfterCapture)
        ),
        refundTransitions(Authorised, 1),
        refundTransitions(ExpiredAfterCapture, 2)
    );
  }

  private TransitionModel<Capture, CaptureRequestData, Capture> captureRequestTransition(State anchor) {
    return onEvent(CaptureRequest).from(anchor).to(ProcessingCapture)
        .withData(new CaptureRequestDataCreator())
        .filter(d -> d.alreadyCapturedAmount() + d.captureData().amount() <= d.authorisationData().amount().requested())
        .orElseInvalidRequest("Capture amount too large")
        .output(CaptureRequestData::captureData)
        .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
            .identifiedBy(model(BatchNumber).group(data.authorisationData().merchant().id()).last()))
        .response(_ -> "", new Created())
        .notify(request(capture()).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse()));
  }

  private TransitionModel<?, ?, ?> captureRequestedTooLateTransition(State anchor) {
    return onEvent(CaptureRequest).from(anchor).toSelf()
        .withData((input, log) -> Mono.just(tuple(
            log.one(PaymentRequest).getUnmarshalledData(),
            log.one(AuthorisationRequest, PreauthorisationRequest).getUnmarshalledData(),
            input.data()
        )))
        .output(Tuple3::t3)
        .notify(request(captureRequestedTooLate()).to(Acquirer).guaranteed())
        .response("Capture requested too late", new BadRequest());
  }

  private List<TransitionModel<?, ?, ?>> refundTransitions(State anchor, int i) {
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
        onEvent(RefundRequest).from(anchor).to(processingState)
            .withData(new RefundRequestDataCreator())
            .filter(d -> d.alreadyRefundedAmount() + d.refundData().amount() <= d.alreadyCapturedAmount())
            .orElseInvalidRequest("Refund amount too large")
            .output(RefundRequestData::refundData)
            .response("", new Created())
            .notify(request(refundAuthorisation()).to(Acquirer).responseValidator(validateRefundResponse()))
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(BuiltinEventTypes.Status).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId()).last()))
                .notify(request(refundReversal()).to(Acquirer)
                    .guaranteed()
                    .responseValidator(validateRefundReversalResponse()))),
        onEvent(Rollback).from(processingState).toSelf().build(),
        onEvent(RollbackRequest).from(processingState).toSelf().response(new Created()),
        onEvent(RequestUndelivered).from(processingState).to(anchor)
            .withData(new FailedRefundDataCreator())
            .notify(request(failedRefund()).to(Merchant).guaranteed()),
        onEvent(RefundApproved).from(processingState).to(anchor)
            .withData(new ApprovedRefundDataCreator())
            .output(ApprovedRefundData::acquirerResponse)
            .notify(request(approvedRefund()).to(Merchant).guaranteed())
            .trigger(data -> event(MerchantDebit, data.amount()).onEntity(settlement).identifiedBy(secondaryId(
                AcquirerBatchNumber,
                new AcquirerBatchNumber(data.merchantId(), data.acquirerResponse().batchNumber())
            ).createIfNotExists()).and(model(BatchNumber).group(data.merchantId()).last().createIfNotExists()))
            .reverse(transition -> transition.withData(new RefundReversalDataCreator())
                .trigger(data -> event(SettlementEvent.MerchantDebitReversed, data.amount()).onEntity(settlement)
                    .identifiedBy(model(BatchNumber).group(data.merchantId()).last()))),
        onEvent(AcquirerDeclined).from(processingState).to(anchor)
            .withData(new DeclinedRefundDataCreator())
            .notify(request(declinedRefund()).to(Merchant).guaranteed())
    );
  }

  @Override
  public EntityModel parentEntity() {
    return settlement;
  }

}
