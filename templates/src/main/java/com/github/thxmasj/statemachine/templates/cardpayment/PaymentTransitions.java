package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.AcceptedRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.InvalidRequest;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.lastInIdGroup;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel.mergeModels;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcceptedCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcceptedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerDeclined;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationExpired;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.DeclinedCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.DeclinedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidAuthenticationToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenOwnership;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenStatus;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Preauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

public abstract class PaymentTransitions {

  private final InboxExchange inboxExchange;

  public PaymentTransitions(InboxExchange inboxExchange) {
    this.inboxExchange = inboxExchange;
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

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return mergeModels(Map.of(
            Begin, List.of(
                onEvent(RollbackRequest).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                onEvent(PaymentRequest).to(ProcessingAuthentication)
                    .assemble((input, _) -> tuple(
                        input.data().t1(),
                        new AuthenticationData(input.data().t2(), input.data().t1().simulation())
                    ))
                    .trigger(Get).on(Aggregate.Merchant).identifiedBy(secondaryId(MerchantId, d -> d.t1().merchantId()))
                    .trigger(authentication()).with(d -> d.t1().t2()).to(Authenticator).responseValidator(validateAuthenticationResponse())
                    .output(data -> tuple(data.t1().t1(), data.t2().event().getUnmarshalledData()))
            ),
            ProcessingAuthentication, List.of(
                onEvent(InvalidAuthenticationToken).to(Begin)
                    .trigger(InvalidRequest).with(_ -> "Invalid authentication token").on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new BadRequest()).with(_ -> "Invalid authentication token")
                    .output(),
                onEvent(InvalidPaymentTokenStatus).to(Begin)
                    .trigger(InvalidRequest).with(_ -> "Payment token is inactive").on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new BadRequest()).with(_ -> "Payment token is inactive")
                    .output(),
                onEvent(AuthenticationFailed).to(Begin)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                    .trigger(failedAuthentication()).with(d -> tuple(d.t1(), d.t2(), d.t3())).to(Acquirer).guaranteed()
                    .trigger(InvalidRequest).with(_ -> "Authentication failed").on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new BadRequest()).with(_ -> "Authentication failed")
                    .output(),
                onEvent(InvalidPaymentTokenOwnership).to(Begin)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                    .trigger(failedTokenValidation()).with(d -> d).to(Acquirer).guaranteed().responseValidator(validateAuthorisationAdviceResponse())
                    .trigger(InvalidRequest).with(_ -> "Payment token is not accessible").on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new BadRequest()).with(_ -> "Payment token is not accessible")
                    .output(),
                onEvent(RollbackRequest).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                onEvent(AuthorisationRequest).to(ProcessingAuthorisation)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest), input.data()))
                    .when(d -> d.t1().t1().capture()).then(
                        onEvent(PaymentEvent.Authorisation).to(ProcessingAuthorisation)
                            .assemble((input, log) -> tuple(log.one(PaymentRequest), input.data()))
                            .trigger(GetAcquirerBatchNumber)
                            .on(Settlement)
                            .identifiedBy(lastInIdGroup(BatchNumber, d -> d.t1().t2().id()))
                            .trigger(authorisation())
                            .with(d -> tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t2().event().getUnmarshalledData(),
                                d.t1().t2()
                            ))
                            .to(Acquirer)
                            .responseValidator(validateAuthorisationResponse())
                            .trigger(AcceptedRequest)
                            .on(inboxExchange)
                            .identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  Authorisation paymentData = log.one(PaymentRequest).t1();
                                  PaymentEvent.Merchant merchant = log.one(PaymentRequest).t2();
                                  AcquirerResponse acquirerResponse = log.lastIfExists(AuthorisationApproved).orElse(null);
                                  return new AuthorisationReversalData(
                                      rollbackType == Cancel || rollbackType == RollbackRequest,
                                      rollbackType != Cancel,
                                      merchant,
                                      paymentData.amount().requested(),
                                      paymentData.merchantReference(),
                                      acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                      paymentData.simulation()
                                  );
                                })
                                    .trigger(GetAcquirerBatchNumber)
                                    .on(Settlement)
                                    .identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
                                    .trigger(authorisationReversal())
                                    .with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData()))
                                    .to(Acquirer)
                                    .guaranteed()
                                    .responseValidator(validateAuthorisationReversalResponse())
                                    .trigger(rolledBackAuthorisationRequest())
                                    .with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData()))
                                    .to(Queues.Merchant)
                                    .guaranteed()
                                    .output()
                            )
                            .output(d -> d.t1().t1().t2()),
                        Tuple2::t2
                    )
                    .when(d -> !d.t1().t1().capture()).then(
                        onEvent(Preauthorisation).to(ProcessingAuthorisation)
                            .assemble(((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data())))
                            .trigger(preauthorisation()).with(d -> d).to(Acquirer).responseValidator(validatePreauthorisationResponse())
                            .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  var paymentData = log.one(PaymentRequest);
                                  AcquirerResponse acquirerResponse = log.lastIfExists(PreauthorisationApproved).orElse(null);
                                  return new PreauthorisationReversalData(
                                      rollbackType == Cancel || rollbackType == RollbackRequest,
                                      rollbackType != Cancel,
                                      paymentData.t2(),
                                      paymentData.t1().amount(),
                                      paymentData.t1().merchantReference(),
                                      acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                      paymentData.t1().simulation()
                                  );
                                })
                                    .trigger(preauthorisationReversal()).with(d -> d).to(Acquirer)
                                    .guaranteed()
                                    .responseValidator(validatePreauthorisationReversalResponse())
                                    .trigger(rolledBackPreauthorisationRequest()).with(d -> d).to(Queues.Merchant).guaranteed()
                                    .output()
                            )
                            .output(d -> d.t1().t3()),
                        Tuple2::t2
                    )
                    .output(Tuple2::t2)

            ),
            ProcessingAuthorisation, List.of(
                onEvent(PreauthorisationApproved).to(Preauthorised)
                    .assemble(((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data())))
                    .trigger(approvedPreauthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .schedule(AuthorisationExpired, Duration.ofDays(7))
                    .output(Tuple3::t3),
                onEvent(Rollback).toSelf().output(),
                onEvent(RollbackRequest).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                onEvent(RequestUndelivered).to(AuthorisationFailed)
                    .assemble((_, log) -> tuple(
                        log.one(PaymentRequest).t1(),
                        log.one(PaymentRequest).t2(),
                        log.one(PaymentEvent.Authorisation, Preauthorisation)
                    ))
                    .trigger(failedAuthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(),
                onEvent(AuthorisationApproved).to(Authorised)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                    .trigger(GetBatchNumber).on(Settlement)
                    .identifiedBy(secondaryId(AcquirerBatchNumber, d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()), CreateIfNotExists))
                    .identifiedBy(lastInIdGroup(BatchNumber, d -> d.t1().merchantId()))
                    .trigger(MerchantCredit).with(d -> d.t1().t1().amount().requested()).on(Settlement)
                    .identifiedBy(secondaryId(BatchNumber, Tuple2::t2))
                    //Tuple4<PaymentEvent.Authorisation, PaymentEvent.Merchant, BatchNumber, AcquirerResponse>
                    .trigger(approvedAuthorisation()).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().event().getUnmarshalledData(), d.t1().t1().t3())).to(
                        Queues.Merchant).guaranteed()
                    .schedule(AuthorisationExpired, Duration.ofDays(7))
                    .reversible(
                        assemble((log, _) -> log.one(PaymentRequest).t1())
                            .trigger(MerchantCreditReversed).with(d -> d.amount().requested()).on(Settlement).identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchantId()))
                            .output()
                    )
                    .output(d -> d.t1().t1().t3()),
//                .reverse(new BaseReversalBuildingBlock()
//                .trigger(
//                    SettlementEvent.MerchantCreditReversed,
//                    t -> t.t2().one(PaymentRequest).t1().amount().requested()
//                ).onEntity(settlement)
//                .identifiedByLastInGroup(
//                    BatchNumber,
//                    (_, log) -> log.one(PaymentRequest).t1().merchantId(),
//                    NeverCreate
//                )),
                onEvent(AcquirerDeclined).to(AuthorisationFailed)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                    .trigger(declinedAuthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output()
            ),
            AuthorisationFailed, List.of(),
            ProcessingCapture, List.of(
                onEvent(CaptureApproved).to(Authorised)
                    .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                    .trigger(GetBatchNumber).on(Settlement)
                    .identifiedBy(secondaryId(AcquirerBatchNumber, d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()), CreateIfNotExists))
                    .identifiedBy(lastInIdGroup(BatchNumber, d -> d.t1().merchantId()))
                    .trigger(MerchantCredit).with(d -> d.t1().t3().amount()).on(Settlement)
                    .identifiedBy(secondaryId(BatchNumber, Tuple2::t2))
                    .trigger(approvedCapture()).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().event().getUnmarshalledData(), d.t1().t1().t3())).to(
                        Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t1().t3())
            ),
            Preauthorised, List.of(
                onEvent(Rollback).toSelf().output(),
                onEvent(AuthorisationExpired).to(Expired).output(),
                onEvent(RollbackRequest).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                onEvent(Cancel).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                captureRequestTransition()
            ),
            Authorised, List.of(
                onEvent(Rollback).toSelf().output(),
                onEvent(RollbackRequest).toSelf()
                    .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                    //.trigger(new Created()).with(_ -> "")
                    .output(),
                onEvent(AuthorisationExpired).to(ExpiredAfterCapture).output(),
                captureRequestTransition()
            ),
            Expired, List.of(captureRequestedTooLateTransition()),
            ExpiredAfterCapture, List.of(captureRequestedTooLateTransition())
        ),
        refundTransitions(Authorised, 1),
        refundTransitions(ExpiredAfterCapture, 2)
    );
  }

  private TransitionModel<?, ?> captureRequestTransition() {
    return onEvent(CaptureRequest).to(ProcessingCapture)
        .assemble((input, log) -> new CaptureRequestData(
            log.one(PaymentRequest).t1(),
            log.one(PaymentRequest).t2(),
            log.one(Preauthorisation),
            log.one(PreauthorisationApproved),
            input.data(),
            log.all(CaptureApproved).stream()
                .map(AcquirerResponse::amount)
                .mapToLong(Long::longValue)
                .sum()
        ))
        .when(d -> d.alreadyCapturedAmount() + d.captureData().amount() <= d.authorisationData().amount().requested())
        .then(
            onEvent(AcceptedCapture).to(ProcessingCapture)
                .assemble((input, log) -> new CaptureRequestData(
                    log.one(PaymentRequest).t1(),
                    log.one(PaymentRequest).t2(),
                    log.one(Preauthorisation),
                    log.one(PreauthorisationApproved),
                    input.data(),
                    log.all(CaptureApproved).stream()
                        .map(AcquirerResponse::amount)
                        .mapToLong(Long::longValue)
                        .sum()
                ))
                .trigger(GetAcquirerBatchNumber).on(Settlement).identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
                .trigger(capture()).with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData())).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse())
                .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().captureData()),
            CaptureRequestData::captureData
        )
        .when(_ -> true)
        .then(
            onEvent(DeclinedCapture).toSelf()
                .trigger(InvalidRequest).with(_ -> "Capture amount too large").on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(),
            CaptureRequestData::captureData
        )
//        .orElse(InvalidRequest, "Capture amount too large")
//        .trigger(GetAcquirerBatchNumber).on(settlement).identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
//        .trigger(capture()).with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData())).to(Acquirer).guaranteed().responseValidator(validateCaptureResponse())
//        .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
        .output(CaptureRequestData::captureData);
  }

  private TransitionModel<?, ?> captureRequestedTooLateTransition() {
    return onEvent(CaptureRequest).toSelf()
        .assemble((input, log) -> tuple(
            log.one(PaymentRequest).t1(),
            log.one(PaymentRequest).t2(),
            log.one(PaymentEvent.Authorisation, Preauthorisation),
            input.data()
        ))
        .trigger(captureRequestedTooLate()).with(d -> d).to(Acquirer).guaranteed()
        .trigger(InvalidRequest).with(_ -> "Capture requested too late").on(inboxExchange).identifiedBy(entityIdFromSession())
        //.trigger(new BadRequest()).with(_ -> "Capture requested too late")
        .output(d -> d.t1().t4());
  }

  private Map<State, List<TransitionModel<?, ?>>> refundTransitions(State anchor, int i) {
    State processingState = new State() {
      @Override
      public String name() {
        return "ProcessingRefund" + i;
      }
      @Override
      public Optional<Timeout> timeout() {
        return Optional.of(new Timeout(Duration.ofMillis(6600), new InputEvent<>(Rollback, new BasicEventType.Rollback.Data(-1, "ProcessingRefund timeout"))));
      }
      @Override
      public boolean equals(Object o) {
        return o instanceof State os && os.name().equals(name());
      }
    };
    return Map.of(
        anchor,
        List.of(
            onEvent(RefundRequest).to(processingState)
                .assemble(refundAssembler)
                .when(d -> d.alreadyRefundedAmount() + d.refundData().amount() <= d.alreadyCapturedAmount())
                .then(onEvent(AcceptedRefund).to(processingState)
                        .assemble(refundAssembler)
                        .trigger(GetAcquirerBatchNumber)
                        .on(Settlement).identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
                        .trigger(refundAuthorisation()).with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData()))
                        .to(Acquirer).responseValidator(validateRefundResponse())
                        .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                        .reversible(
                            assemble((log, rollbackType) -> {
                              var paymentData = log.one(PaymentRequest);
                              Refund refundData = log.last(RefundRequest);
                              AcquirerResponse acquirerResponse = log.lastIfExists(RefundApproved).orElse(null);
                              return new RefundReversalData(
                                  rollbackType == Cancel || rollbackType == RollbackRequest,
                                  rollbackType != Cancel,
                                  paymentData.t2(),
                                  refundData.amount(),
                                  paymentData.t1().merchantReference(),
                                  acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                  paymentData.t1().simulation()
                              );
                            })
                                .trigger(GetAcquirerBatchNumber)
                                .on(Settlement)
                                .identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
                                .trigger(refundReversal())
                                .with(d -> tuple(d.t1(), d.t2().event().getUnmarshalledData()))
                                .to(Acquirer)
                                .guaranteed()
                                .responseValidator(validateRefundReversalResponse())
                                .output()
                        )
                        .output(d -> d.t1().t1().refundData()),
                    RefundRequestData::refundData
                )
                .when(_ -> true).then(
                    onEvent(DeclinedRefund).toSelf()
                        .trigger(InvalidRequest)
                        .with(_ -> "Refund amount too large")
                        .on(inboxExchange)
                        .identifiedBy(entityIdFromSession())
                        .output(),
                    RefundRequestData::refundData
                )
                .output(RefundRequestData::refundData)
        ),
        processingState,
        List.of(
            onEvent(Rollback).toSelf().output(),
            onEvent(RollbackRequest).toSelf()
                .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                //.trigger(new Created()).with(_ -> "")
                .output(),
            onEvent(RequestUndelivered).to(anchor)
                .assemble((_, log) -> log.one(PaymentRequest))
                .trigger(failedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output(),
            onEvent(RefundApproved).to(anchor)
                .assemble((input, log) -> {
                  var paymentData = log.one(PaymentRequest);
                  Refund refundData = log.last(RefundRequest);
                  return new ApprovedRefundData(
                      input.data(),
                      paymentData.t2(),
                      refundData.amount(),
                      paymentData.t1().merchantReference(),
                      refundData.correlationId()
                  );
                })
                .trigger(GetBatchNumber).on(Settlement)
                .identifiedBy(secondaryId(AcquirerBatchNumber, d -> new AcquirerBatchNumber(d.merchant().id(), d.acquirerResponse().batchNumber()), CreateIfNotExists))
                .identifiedBy(lastInIdGroup(BatchNumber, d -> d.merchant().id()))
                .trigger(MerchantDebit).with(d -> d.t1().acquirerResponse().amount()).on(Settlement)
                .identifiedBy(secondaryId(BatchNumber, Tuple2::t2))
                .trigger(approvedRefund()).with(d -> tuple(d.t1().t1(), d.t1().t2().event().getUnmarshalledData())).to(
                    Queues.Merchant).guaranteed()
                .reversible(
                    assemble((log, _) -> tuple(log.last(RefundRequest), log.one(PaymentRequest).t2()))
                        .trigger(MerchantDebitReversed).with(d -> d.t1().amount()).on(Settlement)
                        .identifiedBy(lastInIdGroup(BatchNumber, d -> d.t2().id()))
                        .output()
                )
                .output(d -> d.t1().t1().acquirerResponse()),
            onEvent(AcquirerDeclined).to(anchor)
                .assemble((input, log) -> tuple(log.one(PaymentRequest).t1(), log.one(PaymentRequest).t2(), input.data()))
                .trigger(declinedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output()
        )
    );
  }

  private static final BiFunction<InputEvent<Refund>, EventLog, RefundRequestData> refundAssembler = (input, log) -> {
    Authorisation authorisationData = log.one(PaymentRequest).t1();
    long alreadyCapturedAmount;
    if (authorisationData.capture()) {
      alreadyCapturedAmount = authorisationData.amount().requested();
    } else {
      alreadyCapturedAmount = log.all(CaptureApproved).stream()
          .map(AcquirerResponse::amount)
          .mapToLong(Long::longValue)
          .sum();
    }
    long alreadyRefundedAmount = log.all(RefundApproved).stream()
        .map(AcquirerResponse::amount)
        .mapToLong(Long::longValue)
        .sum();
    return new RefundRequestData(
        authorisationData,
        log.one(PaymentRequest).t2(),
        log.one(PaymentEvent.Authorisation, Preauthorisation),
        input.data(),
        alreadyCapturedAmount,
        alreadyRefundedAmount,
        input.data().simulation()
    );
  };


}
