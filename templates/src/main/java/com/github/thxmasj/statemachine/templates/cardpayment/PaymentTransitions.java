package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.RequestUndelivered;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.lastInIdGroup;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel.mergeModels;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteInvalidRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerDeclined;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Cancel;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.CaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.DeclineLateCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.DeclinedRefund;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.DeclinedUnauthorisedCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.IllegalMerchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InsufficientMerchantDetails;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidAmount;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidAuthenticationToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenOwnership;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidPaymentTokenStatus;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.InvalidTransactionTime;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Preauthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PreauthorisationApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundApproved;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.RollbackRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.UnknownMerchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.ValidCaptureRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.ValidPaymentRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.ValidRefundRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AuthorisationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Authorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Preauthorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount.validateAmount;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime.validateTransactionTime;

import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.Tuples.Tuple5;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.templates.cardpayment.ApprovedRefundDataCreator.ApprovedRefundData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthorisationReversalDataCreator.AuthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation.MerchantDetails;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.PreauthorisationReversalDataCreator.PreauthorisationReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundReversalDataCreator.RefundReversalData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class PaymentTransitions {

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

  protected abstract PaymentToken paymentToken(String encryptedAuthenticationData);

  public Map<State, List<TransitionModel<?, ?>>> transitions(
      // validateAuthenticationResponse() - no rollback
      AtMostOnce<AuthenticationData, ?, ?, ?, ?> authenticator,
      AtLeastOnce<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, ?, ?> failedAuthenticationToAcquirer,
      // validateAuthorisationAdviceResponse()
      AtLeastOnce<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, ?, ?> failedTokenValidationToAcquirer,
      // validateAuthorisationResponse()
      AtMostOnce<Tuple5<Authorisation, Merchant, AcquirerBatchNumber, AuthenticationResult, PaymentToken>, ?, ?, ?, ?> authorisationToAcquirer,
      //
      AtLeastOnce<Tuple2<AuthorisationReversalData, BatchNumber>, ?, ?, ?> rolledBackAuthorisationRequestToMerchant,
      // validatePreauthorisationResponse()
      AtMostOnce<Tuple4<PaymentEvent.Authorisation, PaymentEvent.Merchant, AuthenticationResult, PaymentToken>, ?, ?, ?, ?> preauthorisationToAcquirer,
      //
      AtLeastOnce<PreauthorisationReversalData, ?, ?, ?> rolledBackPreauthorisationRequestToMerchant,
      //
      AtLeastOnce<Tuple3<PaymentEvent.Authorisation, PaymentEvent.Merchant, AcquirerResponse>, ?, ?, ?> approvedPreauthorisationToMerchant,
      //
      AtLeastOnce<Tuple3<PaymentEvent.Authorisation, PaymentEvent.Merchant, AuthenticationResult>, ?, ?, ?> failedAuthorisationToMerchant,
      //
      AtLeastOnce<Tuple4<PaymentEvent.Authorisation, PaymentEvent.Merchant, BatchNumber, AcquirerResponse>, ?, ?, ?> approvedAuthorisationToMerchant,
      //
      AtLeastOnce<Tuple3<PaymentEvent.Authorisation, PaymentEvent.Merchant, AcquirerResponse>, ?, ?, ?> declinedAuthorisationToMerchant,
      //
      AtLeastOnce<Tuple4<PaymentEvent.Authorisation, PaymentEvent.Merchant, BatchNumber, AcquirerResponse>, ?, ?, ?> approvedCaptureToMerchant,
      //
      AtLeastOnce<Tuple5<PaymentEvent.Authorisation, PaymentEvent.Merchant, AuthenticationResult, PaymentEvent.Capture, PaymentToken>, ?, ?, ?> captureRequestedTooLateToAcquirer,
      // validateCaptureResponse()
      AtLeastOnce<Tuple3<CaptureRequestData, AcquirerBatchNumber, PaymentToken>, ?, ?, ?> captureToAcquirer,
      //
      AtLeastOnce<Tuple2<PaymentEvent.Authorisation, PaymentEvent.Merchant>, ?, ?, ?> failedRefundToMerchant,
      //
      AtLeastOnce<Tuple2<ApprovedRefundData, BatchNumber>, ?, ?, ?> approvedRefundToMerchant,
      //
      AtLeastOnce<Tuple3<PaymentEvent.Authorisation, PaymentEvent.Merchant, AcquirerResponse>, ?, ?, ?> declinedRefundToMerchant,
      // validateRefundResponse()
      AtMostOnce<Tuple3<RefundRequestData, AcquirerBatchNumber, PaymentToken>, ?, ?, ?, ?> refundAuthorisationToAcquirer,
      // validateAuthorisationReversalResponse()
      AtLeastOnce<Tuple2<AuthorisationReversalData, AcquirerBatchNumber>, ?, ?, ?> authorisationReversalToAcquirer,
      // validatePreauthorisationReversalResponse()
      AtLeastOnce<PreauthorisationReversalData, ?, ?, ?> preauthorisationReversalToAcquirer,
      // validateRefundReversalResponse()
      AtLeastOnce<Tuple2<RefundReversalData, AcquirerBatchNumber>, ?, ?, ?> refundReversalToAcquirer
    ) {
    return mergeModels(Map.of(
            Begin, List.of(
                onEvent(PaymentRequest).to(ProcessingAuthentication)
                    .assemble(c -> tuple(
                        c.input().t1(),
                        new AuthenticationData(c.input().t2(), c.input().t1().simulation()),
                        validateAmount(c.input().t1()),
                        validateTransactionTime(c.input().t1(), c.timestamp())
                    ))
                    .trigger(Get).on(Aggregate.Merchant).identifiedBy(d -> secondaryId(MerchantId, d.t1().merchantId()))
                    .when(d -> d.t2().isUnknownId()).then(
                        onEvent(UnknownMerchant).toSelf()
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Unknown merchant " + d.t1().value(), d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> new MerchantId(d.t1().t1().merchantId())
                    )
                    .when(d -> !d.t2().accepted().event().getUnmarshalledData().aggregatorId().equals(d.t1().t1().clientId())).then(
                        onEvent(IllegalMerchant).toSelf()
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Unauthorized access to merchant " + d.t1().value(), d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> new MerchantId(d.t1().t1().merchantId())
                    )
                    .when(d -> d.t2().accepted().event().getUnmarshalledData().superMerchant() && !validateMerchantDetails(d.t1().t1().merchantDetails())).then(
                        onEvent(InsufficientMerchantDetails).toSelf()
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Insufficient details for merchant provided", d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(),
                        _ -> null
                    )
                    .when(d -> d.t1().t3().isInvalid()).then(
                        onEvent(InvalidAmount).toSelf()
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple(d.t1().error(), d.t2()))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> d.t1().t3().invalid()
                    )
                    .when(d -> d.t1().t4().isInvalid()).then(
                        onEvent(InvalidTransactionTime).toSelf()
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple(d.t1().error(), d.t2()))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> d.t1().t4().invalid()
                    )
                    .otherwise(
                        onEvent(ValidPaymentRequest).to(ProcessingAuthentication)
                            .assembleInput()
//                            .trigger(authentication()).with(Tuple3::t3).to(Authenticator).responseValidator(validateAuthenticationResponse())
                            .trigger(authenticator.sendRequest()).with(Tuple3::t3).on(authenticator).identifiedBy(newEntityId())
                            .output(d -> tuple(d.t1().t1(), merchant(d.t1().t2(), d.t1().t1().merchantDetails()))),
                        d -> tuple(d.t1().t1(), d.t2().accepted().event().getUnmarshalledData(), d.t1().t2())
                    )
            ),
            ProcessingAuthentication, List.of(
                onEvent(InvalidAuthenticationToken).to(Begin)
                    .assemble(c -> tuple(c.input(), c.eventReference()))
                    .trigger(CompleteInvalidRequest)
                    .with(d -> tuple("Invalid authentication token", d.t2()))
                    .on(RequestDispatching)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(InvalidPaymentTokenStatus).to(Begin)
                    .assemble(c -> tuple(c.input(), c.eventReference()))
                    .trigger(CompleteInvalidRequest)
                    .with(d -> tuple("Payment token is inactive", d.t2()))
                    .on(RequestDispatching)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(AuthenticationFailed).to(Begin)
                    .assemble(c -> tuple(c.log().one(ValidPaymentRequest).t1(), c.log().one(ValidPaymentRequest).t2(), c.input(), c.eventReference()))
                    //.trigger(failedAuthentication()).with(d -> tuple(d.t1(), d.t2(), d.t3(), paymentToken(d.t1().authenticationData()))).to(Acquirer).guaranteed()
                    .trigger(failedAuthenticationToAcquirer.sendRequest()).with(d -> tuple(d.t1(), d.t2(), d.t3(), paymentToken(d.t1().authenticationData()))).on(failedAuthenticationToAcquirer).identifiedBy(newEntityId())
                    .trigger(CompleteInvalidRequest)
                    .with(d -> tuple("Authentication failed", d.t1().t4()))
                    .on(RequestDispatching)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(InvalidPaymentTokenOwnership).to(Begin)
                    .assemble(c -> tuple(
                        c.log().one(ValidPaymentRequest).t1(),
                        c.log().one(ValidPaymentRequest).t2(),
                        c.input(),
                        paymentToken(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                        c.eventReference()
                    ))
//                    .trigger(failedTokenValidation()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).to(Acquirer).guaranteed().responseValidator(validateAuthorisationAdviceResponse())
                    .trigger(failedTokenValidationToAcquirer.sendRequest()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).on(failedTokenValidationToAcquirer).identifiedBy(newEntityId())
                    .trigger(CompleteInvalidRequest)
                    .with(d -> tuple("Payment token is not accessible", d.t1().t5()))
                    .on(RequestDispatching)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(PaymentEvent.Authorisation).to(ProcessingAuthorisation)
                    .assemble((input, log) -> tuple(log.one(ValidPaymentRequest), input))
                    .when(d -> d.t1().t1().capture()).then(
                        onEvent(PaymentEvent.Authorisation).to(ProcessingAuthorisation)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest),
                                c.input(),
                                c.eventReference()
                            ))
                            .trigger(GetAcquirerBatchNumber)
                            .with(d -> new MerchantId(d.t1().t2().id()))
                            .on(Settlement)
                            .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t1().t2().id(), CreateIfNotExists))
                            .trigger(authorisationToAcquirer.sendRequest())
                            .with(d -> tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t2().accepted().event().getUnmarshalledData(),
                                d.t1().t2(),
                                paymentToken(d.t1().t1().t1().authenticationData())
                            ))
                            .on(authorisationToAcquirer).identifiedBy(newEntityId())
//                            .trigger(authorisation())
//                            .with(d -> tuple(
//                                d.t1().t1().t1(),
//                                d.t1().t1().t2(),
//                                d.t2().accepted().event().getUnmarshalledData(),
//                                d.t1().t2(),
//                                paymentToken(d.t1().t1().t1().authenticationData())
//                            ))
//                            .to(Acquirer)
//                            .responseValidator(validateAuthorisationResponse())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t3())).on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  Authorisation paymentData = log.one(ValidPaymentRequest).t1();
                                  PaymentEvent.Merchant merchant = log.one(ValidPaymentRequest).t2();
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
                                    .with(d -> new MerchantId(d.merchant().id()))
                                    .on(Settlement)
                                    .identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchant().id(), CreateIfNotExists))
                                    .trigger(GetBatchNumber)
                                    .with(d -> d.t2().accepted().event().getUnmarshalledData())
                                    .on(Settlement)
                                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                                    .trigger(authorisationReversalToAcquirer.sendRequest())
                                    .with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    .on(authorisationReversalToAcquirer)
                                    .identifiedBy(newEntityId())
//                                    .trigger(authorisationReversal())
//                                    .with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
//                                    .to(Acquirer)
//                                    .guaranteed()
//                                    .responseValidator(validateAuthorisationReversalResponse())
                                    .trigger(rolledBackAuthorisationRequestToMerchant.sendRequest())
                                    .with(d -> tuple(d.t1().t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    .on(rolledBackAuthorisationRequestToMerchant)
                                    .identifiedBy(newEntityId())
//                                    .trigger(rolledBackAuthorisationRequest())
//                                    .with(d -> tuple(d.t1().t1(), d.t2().accepted().event().getUnmarshalledData()))
//                                    .to(Queues.Merchant)
//                                    .guaranteed()
                                    //.complete()
                            )
                            .output(d -> d.t1().t1().t1().t2()),
                        Tuple2::t2
                    )
                    .when(d -> !d.t1().t1().capture()).then(
                        onEvent(Preauthorisation).to(ProcessingAuthorisation)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest).t1(),
                                c.log().one(ValidPaymentRequest).t2(),
                                c.input(),
                                paymentToken(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                                c.eventReference()
                            ))
                            .trigger(preauthorisationToAcquirer.sendRequest()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).on(preauthorisationToAcquirer).identifiedBy(newEntityId())
//                            .trigger(preauthorisation()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).to(Acquirer).responseValidator(validatePreauthorisationResponse())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  var paymentData = log.one(ValidPaymentRequest);
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
                                    .trigger(preauthorisationReversalToAcquirer.sendRequest())
                                    .with(d -> d)
                                    .on(preauthorisationReversalToAcquirer)
                                    .identifiedBy(newEntityId())
//                                    .trigger(preauthorisationReversal()).with(d -> d).to(Acquirer)
//                                    .guaranteed()
//                                    .responseValidator(validatePreauthorisationReversalResponse())
                                    .trigger(rolledBackPreauthorisationRequestToMerchant.sendRequest())
                                    .with(Tuple2::t1)
                                    .on(rolledBackPreauthorisationRequestToMerchant)
                                    .identifiedBy(newEntityId())
//                                    .trigger(rolledBackPreauthorisationRequest()).with(d -> d).to(Queues.Merchant).guaranteed()
//                                    .complete()
                            )
                            .output(d -> d.t1().t1().t3()),
                        Tuple2::t2
                    )
                    .output(Tuple2::t2)

            ),
            ProcessingAuthorisation, List.of(
                onEvent(PreauthorisationApproved).to(Preauthorised)
                    .assemble(((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input)))
                    .trigger(approvedPreauthorisationToMerchant.sendRequest()).with(d -> d).on(approvedPreauthorisationToMerchant).identifiedBy(newEntityId())
//                    .trigger(approvedPreauthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t3()),
                onEvent(RequestUndelivered).to(AuthorisationFailed)
                    .assemble((_, log) -> tuple(
                        log.one(ValidPaymentRequest).t1(),
                        log.one(ValidPaymentRequest).t2(),
                        log.one(PaymentEvent.Authorisation, Preauthorisation)
                    ))
                    .trigger(failedAuthorisationToMerchant.sendRequest()).with(d -> d).on(failedAuthorisationToMerchant).identifiedBy(newEntityId())
//                    .trigger(failedAuthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(),
                onEvent(AuthorisationApproved).to(Authorised)
                    .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                    .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber())).on(Settlement)
                        .identifiedBy(
                            d -> secondaryId(AcquirerBatchNumber, new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()), CreateIfNotExists)
                        )
                    .trigger(MerchantCredit).with(d -> d.t1().t1().amount().requested()).on(Settlement)
                        .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                    .trigger(approvedAuthorisationToMerchant.sendRequest())
                    .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3()))
                    .on(approvedAuthorisationToMerchant)
                    .identifiedBy(newEntityId())
//                    .trigger(approvedAuthorisation()).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3())).to(
//                        Queues.Merchant).guaranteed()
                    .reversible(
                        assemble((log, _) -> log.one(ValidPaymentRequest).t1())
                            .trigger(MerchantCreditReversed).with(d -> d.amount().requested()).on(Settlement)
                            .identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchantId()))
                    )
                    .output(d -> d.t1().t1().t1().t3()),
                onEvent(AcquirerDeclined).to(AuthorisationFailed)
                    .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                    .trigger(declinedAuthorisationToMerchant.sendRequest()).with(d -> d).on(declinedAuthorisationToMerchant).identifiedBy(newEntityId())
//                    .trigger(declinedAuthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t3())
            ),
            AuthorisationFailed, List.of(),
            ProcessingCapture, List.of(
                onEvent(CaptureApproved).to(Authorised)
                    .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                    .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber())).on(Settlement)
                        .identifiedBy(
                            d -> secondaryId(AcquirerBatchNumber, new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()), CreateIfNotExists),
                            d -> lastInIdGroup(BatchNumber, d.t1().merchantId(), CreateIfNotExists)
                        )
                    .trigger(MerchantCredit).with(d -> d.t1().t3().amount()).on(Settlement)
                        .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                    .trigger(approvedCaptureToMerchant.sendRequest())
                    .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3()))
                    .on(approvedCaptureToMerchant)
                    .identifiedBy(newEntityId())
//                    .trigger(approvedCapture()).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3())).to(
//                        Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t1().t1().t3())
            ),
            Preauthorised, List.of(
                rollbackOn(Cancel),
                captureRequestTransition(captureRequestedTooLateToAcquirer, captureToAcquirer)
            ),
            Authorised, List.of(
                captureRequestTransition(captureRequestedTooLateToAcquirer, captureToAcquirer)
            )
        ),
        refundTransitions(
            Authorised,
            1,
            refundAuthorisationToAcquirer,
            refundReversalToAcquirer,
            failedRefundToMerchant,
            approvedRefundToMerchant,
            declinedRefundToMerchant
        )
    );
  }

  private Merchant merchant(Merchant merchant, MerchantDetails merchantDetails) {
    return merchant.superMerchant() ?
        new Merchant(
            merchant.aggregatorId(),
            merchant.id(),
            merchantDetails.displayName(),
            merchantDetails.location(),
            merchantDetails.categoryCode(),
            merchant.acquirerId(),
            true
        ) : merchant;
  }

  private boolean validateMerchantDetails(MerchantDetails merchantDetails) {
    return merchantDetails != null && merchantDetails.categoryCode() != null && merchantDetails.displayName() != null
        && merchantDetails.location() != null && merchantDetails.location().city() != null;
  }

  private TransitionModel<?, ?> captureRequestTransition(
      AtLeastOnce<Tuple5<PaymentEvent.Authorisation, PaymentEvent.Merchant, AuthenticationResult, PaymentEvent.Capture, PaymentToken>, ?, ?, ?> captureRequestedTooLateToAcquirer,
      AtLeastOnce<Tuple3<CaptureRequestData, AcquirerBatchNumber, PaymentToken>, ?, ?, ?> captureToAcquirer
  ) {
    return onEvent(CaptureRequest).to(ProcessingCapture)
        .assemble(c -> new CaptureRequestData(
            c.log().one(ValidPaymentRequest).t1(),
            c.log().one(ValidPaymentRequest).t2(),
            c.log().one(Preauthorisation),
            c.log().one(PreauthorisationApproved),
            c.input(),
            c.log().all(CaptureApproved).stream()
                .map(AcquirerResponse::amount)
                .mapToLong(Long::longValue)
                .sum(),
            c.log().entityId(),
            c.timestamp()
        ))
        .when(d -> d.alreadyCapturedAmount() + d.captureData().amount() > d.authorisationData().amount().requested())
        .then(
            onEvent(DeclinedUnauthorisedCapture).toSelf()
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(CompleteInvalidRequest).with(d -> tuple("Capture amount too large", d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            CaptureRequestData::captureData
        )
        .when(d -> !d.captureTime().isBefore(d.authorisationData().transactionTime().plusDays(7)))
        .then(
            onEvent(DeclineLateCapture).toSelf()
                .assemble(c -> tuple(
                    c.log().one(ValidPaymentRequest).t1(),
                    c.log().one(ValidPaymentRequest).t2(),
                    c.log().one(PaymentEvent.Authorisation, Preauthorisation),
                    c.input(),
                    paymentToken(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                    c.eventReference()
                ))
                .trigger(captureRequestedTooLateToAcquirer.sendRequest())
                .with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4(), d.t5()))
                .on(captureRequestedTooLateToAcquirer)
                .identifiedBy(newEntityId())
//                .trigger(captureRequestedTooLate())
//                .with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4(), d.t5()))
//                .to(Acquirer).guaranteed()
                .trigger(CompleteInvalidRequest)
                .with(d -> tuple("Capture on expired authorisation", d.t1().t6()))
                .on(RequestDispatching)
                .identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t4()),
            CaptureRequestData::captureData
        )
        .otherwise(
            onEvent(ValidCaptureRequest).to(ProcessingCapture)
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .trigger(GetAcquirerBatchNumber)
                .with(d -> new MerchantId(d.t1().merchant().id()))
                .on(Settlement).identifiedBy(d -> lastInIdGroup(BatchNumber, d.t1().merchant().id(), CreateIfNotExists))
                .trigger(captureToAcquirer.sendRequest())
                .with(d -> tuple(
                    d.t1().t1(),
                    d.t2().isAccepted() ? d.t2().accepted().event().getUnmarshalledData() : new AcquirerBatchNumber(d.t1().t1().merchant().id(), 1),
                    paymentToken(d.t1().t1().authorisationData().authenticationData())
                ))
                .on(captureToAcquirer)
                .identifiedBy(newEntityId())
//                .trigger(capture())
//                .with(d -> tuple(
//                    d.t1().t1(),
//                    d.t2().isAccepted() ? d.t2().accepted().event().getUnmarshalledData() : new AcquirerBatchNumber(d.t1().t1().merchant().id(), 1),
//                    paymentToken(d.t1().t1().authorisationData().authenticationData())
//                ))
//                .to(Acquirer)
//                .guaranteed()
//                .responseValidator(validateCaptureResponse())
                .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t1().captureData())
        );
  }

  private Map<State, List<TransitionModel<?, ?>>> refundTransitions(
      State anchor,
      int i,
      AtMostOnce<Tuple3<RefundRequestData, AcquirerBatchNumber, PaymentToken>, ?, ?, ?, ?> refundAuthorisationToAcquirer,
      AtLeastOnce<Tuple2<RefundReversalData, AcquirerBatchNumber>, ?, ?, ?> refundReversalToAcquirer,
      AtLeastOnce<Tuple2<PaymentEvent.Authorisation, PaymentEvent.Merchant>, ?, ?, ?> failedRefundToMerchant,
      AtLeastOnce<Tuple2<ApprovedRefundData, BatchNumber>, ?, ?, ?> approvedRefundToMerchant,
      AtLeastOnce<Tuple3<PaymentEvent.Authorisation, PaymentEvent.Merchant, AcquirerResponse>, ?, ?, ?> declinedRefundToMerchant
  ) {
    State processingState = new State() {
      @Override
      public String name() {
        return "ProcessingRefund" + i;
      }
      @Override
      public Timeout<?> timeout() {
        return rollbackAfter(Duration.ofMillis(6600));
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
                .then(
                    onEvent(ValidRefundRequest).to(processingState)
                        .assemble(refundAssembler)
                        .trigger(GetAcquirerBatchNumber)
                        .with(d -> new MerchantId(d.merchant().id()))
                        .on(Settlement).identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchant().id()))
                        .trigger(refundAuthorisationToAcquirer.sendRequest())
                        .with(d -> tuple(d.t1(), d.t2().accepted().event().getUnmarshalledData(), d.t1().paymentToken()))
                        .on(refundAuthorisationToAcquirer)
                        .identifiedBy(newEntityId())
//                        .trigger(refundAuthorisation())
//                        .with(d -> tuple(d.t1(), d.t2().accepted().event().getUnmarshalledData(), d.t1().paymentToken()))
//                        .to(Acquirer).responseValidator(validateRefundResponse())
                        .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().eventReference())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .reversible(
                            assemble((log, rollbackType) -> {
                              var paymentData = log.one(ValidPaymentRequest);
                              Refund refundData = log.last(ValidRefundRequest);
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
                                .with(d -> new MerchantId(d.merchant().id()))
                                .on(Settlement)
                                .identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchant().id()))
                                .trigger(refundReversalToAcquirer.sendRequest())
                                .with(d -> tuple(d.t1(), d.t2().accepted().event().getUnmarshalledData()))
                                .on(refundReversalToAcquirer)
                                .identifiedBy(newEntityId())
//                                .trigger(refundReversal())
//                                .with(d -> tuple(d.t1(), d.t2().accepted().event().getUnmarshalledData()))
//                                .to(Acquirer)
//                                .guaranteed()
//                                .responseValidator(validateRefundReversalResponse())
//                                .complete()
                        )
                        .output(d -> d.t1().t1().t1().refundData()),
                    RefundRequestData::refundData
                )
                .when(_ -> true).then(
                    onEvent(DeclinedRefund).toSelf()
                        .assemble(TransitionContext::eventReference)
                        .trigger(CompleteInvalidRequest)
                        .with(d -> tuple("Refund amount too large", d))
                        .on(RequestDispatching)
                        .identifiedBy(entityIdFromSession())
                        .output(),
                    RefundRequestData::refundData
                )
                .output(RefundRequestData::refundData)
        ),
        processingState,
        List.of(
            onEvent(RequestUndelivered).to(anchor)
                .assemble((_, log) -> log.one(ValidPaymentRequest))
                .trigger(failedRefundToMerchant.sendRequest())
                .with(d -> d)
                .on(failedRefundToMerchant)
                .identifiedBy(newEntityId())
//                .trigger(failedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output(),
            onEvent(RefundApproved).to(anchor)
                .assemble((input, log) -> {
                  var paymentData = log.one(ValidPaymentRequest);
                  Refund refundData = log.last(ValidRefundRequest);
                  return new ApprovedRefundData(
                      input,
                      paymentData.t2(),
                      refundData.amount(),
                      paymentData.t1().merchantReference()
                  );
                })
                .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.merchant().id(), d.acquirerResponse().batchNumber())).on(Settlement)
                    .identifiedBy(
                        d -> secondaryId(AcquirerBatchNumber, new AcquirerBatchNumber(d.merchant().id(), d.acquirerResponse().batchNumber()), CreateIfNotExists),
                        d -> lastInIdGroup(BatchNumber, d.merchant().id())
                    )
                .trigger(MerchantDebit).with(d -> d.t1().acquirerResponse().amount()).on(Settlement)
                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                .trigger(approvedRefundToMerchant.sendRequest())
                .with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
                .on(approvedRefundToMerchant)
                .identifiedBy(newEntityId())
//                .trigger(approvedRefund()).with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData())).to(
//                    Queues.Merchant).guaranteed()
                .reversible(
                    assemble((log, _) -> tuple(log.last(ValidRefundRequest), log.one(ValidPaymentRequest).t2()))
                        .trigger(MerchantDebitReversed).with(d -> d.t1().amount()).on(Settlement)
                        .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id()))
                )
                .output(d -> d.t1().t1().t1().acquirerResponse()),
            onEvent(AcquirerDeclined).to(anchor)
                .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                .trigger(declinedRefundToMerchant.sendRequest())
                .with(d -> d)
                .on(declinedRefundToMerchant)
                .identifiedBy(newEntityId())
//                .trigger(declinedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output(d -> d.t1().t3())
        )
    );
  }

  private final Function<TransitionContext<Refund>, RefundRequestData> refundAssembler = c -> {
    Authorisation authorisationData = c.log().one(ValidPaymentRequest).t1();
    long alreadyCapturedAmount;
    if (authorisationData.capture()) {
      alreadyCapturedAmount = authorisationData.amount().requested();
    } else {
      alreadyCapturedAmount = c.log().all(CaptureApproved).stream()
          .map(AcquirerResponse::amount)
          .mapToLong(Long::longValue)
          .sum();
    }
    long alreadyRefundedAmount = c.log().all(RefundApproved).stream()
        .map(AcquirerResponse::amount)
        .mapToLong(Long::longValue)
        .sum();
    return new RefundRequestData(
        authorisationData,
        c.log().one(ValidPaymentRequest).t2(),
        c.log().one(PaymentEvent.Authorisation, Preauthorisation),
        paymentToken(authorisationData.authenticationData()),
        c.input(),
        alreadyCapturedAmount,
        alreadyRefundedAmount,
        c.input().simulation(),
        c.eventReference()
    );
  };

}
