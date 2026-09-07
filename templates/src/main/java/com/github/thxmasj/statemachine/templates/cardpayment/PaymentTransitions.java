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
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationUnavailable;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AuthenticationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AuthorisationFailed;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Authorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Preauthorised;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthentication;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingAuthorisation;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingCapture;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Rejected;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount.validateAmount;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime.validateTransactionTime;

import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.Tuples.Tuple5;
import com.github.thxmasj.statemachine.Tuples.Tuple6;
import com.github.thxmasj.statemachine.Tuples.Tuple7;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.templates.cardpayment.AuthenticationDataCreator.AuthenticationData;
import com.github.thxmasj.statemachine.templates.cardpayment.CaptureRequestDataCreator.CaptureRequestData;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerAuthorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerRefund;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Authorisation.MerchantDetails;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Capture;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.FailedAuthenticationResult;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.FailedAuthenticationResult.Status;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.PaymentToken;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Refund;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.ReversalData;
import com.github.thxmasj.statemachine.templates.cardpayment.RefundRequestDataCreator.RefundRequestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class PaymentTransitions {
  
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final Function<String, PaymentToken> tokenDecrypter;
  
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;  
  }

  public PaymentTransitions(
      Function<String, PaymentToken> tokenDecrypter,
      AtMostOnce<AuthenticationData, Void> authenticator,
      AtLeastOnce<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>> failedAuthenticationToAcquirer,
      AtMostOnce<Tuple5<Authorisation,  Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber>, Tuple3<ReversalData, Merchant, AcquirerBatchNumber>> authorisationToAcquirer,
      AtMostOnce<Tuple4<Authorisation,  Merchant, AuthenticationResult, PaymentToken>, Tuple2<ReversalData, Merchant>> preauthorisationToAcquirer,
      AtLeastOnce<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>> captureRequestedTooLateToAcquirer,
      AtLeastOnce<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture>> captureToAcquirer,
      AtMostOnce<Tuple6<Authorisation,  Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund>, Tuple3<ReversalData, Merchant, AcquirerBatchNumber>> refundAuthorisationToAcquirer,

      AtLeastOnce<Tuple3<ReversalData,  Merchant, BatchNumber                  >> rolledBackAuthorisationRequestToMerchant,
      AtLeastOnce<Tuple2<ReversalData,  Merchant                               >> rolledBackPreauthorisationRequestToMerchant,
      AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse             >> approvedPreauthorisationToMerchant,
      AtLeastOnce<Tuple2<Authorisation, Merchant                               >> failedAuthorisationToMerchant,
      AtLeastOnce<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>> approvedAuthorisationToMerchant,
      AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse             >> declinedAuthorisationToMerchant,
      AtLeastOnce<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>> approvedCaptureToMerchant,
      AtLeastOnce<Tuple2<Authorisation, Merchant                               >> failedRefundToMerchant,
      AtLeastOnce<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>> approvedRefundToMerchant,
      AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse             >> declinedRefundToMerchant
  ) {
    this.tokenDecrypter = tokenDecrypter;
    this.transitions = mergeModels(Map.of(
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
                        onEvent(UnknownMerchant).to(Rejected)
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Unknown merchant " + d.t1().value(), d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> new MerchantId(d.t1().t1().merchantId())
                    )
                    .when(d -> !d.t2().accepted().event().getUnmarshalledData().aggregatorId().equals(d.t1().t1().clientId())).then(
                        onEvent(IllegalMerchant).to(Rejected)
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Unauthorized access to merchant " + d.t1().value(), d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> new MerchantId(d.t1().t1().merchantId())
                    )
                    .when(d -> d.t2().accepted().event().getUnmarshalledData().superMerchant() && !validateMerchantDetails(d.t1().t1().merchantDetails())).then(
                        onEvent(InsufficientMerchantDetails).to(Rejected)
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Insufficient details for merchant provided", d.t2()))
                            .on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(),
                        _ -> null
                    )
                    .when(d -> d.t1().t3().isInvalid()).then(
                        onEvent(InvalidAmount).to(Rejected)
                            .assemble(c -> tuple(c.input(), c.eventReference()))
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple(d.t1().error(), d.t2()))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> d.t1().t3().invalid()
                    )
                    .when(d -> d.t1().t4().isInvalid()).then(
                        onEvent(InvalidTransactionTime).to(Rejected)
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
                            .trigger(authenticator.requestDispatched()).with(Tuple3::t3).on(authenticator).identifiedBy(newEntityId())
                            .output(d -> tuple(d.t1().t1(), merchant(d.t1().t2(), d.t1().t1().merchantDetails()))),
                        d -> tuple(d.t1().t1(), d.t2().accepted().event().getUnmarshalledData(), d.t1().t2())
                    )
            ),
            ProcessingAuthentication, List.of(
                onEvent(AuthenticationUnavailable).to(AuthenticationFailed)
                    .assemble(c -> c.eventReference())
                    .trigger(CompleteInvalidRequest)
                    .with(d -> tuple("Authentication unavailable", d))
                    .on(RequestDispatching)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(PaymentEvent.AuthenticationFailed).to(AuthenticationFailed)
                    .assemble(c -> tuple(c.input(), c.eventReference()))
                    .when(d -> d.t1().status() == Status.InvalidAuthenticationToken)
                    .then(
                        onEvent(InvalidAuthenticationToken).to(AuthenticationFailed)
                            .assemble(c -> c.eventReference())
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Invalid authentication token", d))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(),
                        _ -> null
                    )
                    .when(d -> d.t1().status() == Status.InvalidPaymentTokenStatus)
                    .then(
                        onEvent(InvalidPaymentTokenStatus).to(AuthenticationFailed)
                            .assemble(c -> c.eventReference())
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Payment token is inactive", d))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(),
                        _ -> null
                    )
                    .when(d -> d.t1().status() == Status.InvalidAuthentication)
                    .then(
                        onEvent(PaymentEvent.InvalidAuthentication).to(AuthenticationFailed)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest).t1(),
                                c.log().one(ValidPaymentRequest).t2(),
                                c.input(),
                                c.eventReference()
                            ))
                            .trigger(failedAuthenticationToAcquirer.requestDispatched())
                            .with(d -> tuple(d.t1(), d.t2(), d.t3(), tokenDecrypter.apply(d.t1().authenticationData())))
                            .on(failedAuthenticationToAcquirer)
                            .identifiedBy(newEntityId())
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Authentication failed", d.t1().t4()))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(),
                        d -> d.t1()
                    )
                    .when(d -> d.t1().status() == Status.InvalidPaymentTokenOwnership)
                    .then(
                        onEvent(InvalidPaymentTokenOwnership).to(AuthenticationFailed)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest).t1(),
                                c.log().one(ValidPaymentRequest).t2(),
                                c.input(),
                                tokenDecrypter.apply(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                                c.eventReference()
                            ))
                            .trigger(failedAuthenticationToAcquirer.requestDispatched())
                            .with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4()))
                            .on(failedAuthenticationToAcquirer)
                            .identifiedBy(newEntityId())
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Payment token is not accessible", d.t1().t5()))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(),
                        d -> d.t1()
                    )
                    .otherwise(
                        // TODO: We could avoid this if we allowed a switch over Status.
                        //       How can we maintain the visual model/diagram for that?
                        onEvent(AuthenticationUnavailable).to(AuthenticationFailed)
                            .assemble(c -> c.eventReference())
                            .trigger(CompleteInvalidRequest)
                            .with(d -> tuple("Unknown authentication status", d))
                            .on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .output(),
                        _ -> null
                    ),
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
                            .trigger(authorisationToAcquirer.requestDispatched())
                            .with(d -> tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t1().t2(),
                                tokenDecrypter.apply(d.t1().t1().t1().authenticationData()),
                                d.t2().accepted().event().getUnmarshalledData()
                            ))
                            .on(authorisationToAcquirer)
                            .identifiedBy(newEntityId())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t3())).on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  Authorisation paymentData = log.one(ValidPaymentRequest).t1();
                                  PaymentEvent.Merchant merchant = log.one(ValidPaymentRequest).t2();
                                  AcquirerResponse acquirerResponse = log.lastIfExists(AuthorisationApproved).orElse(null);
                                  UUID acquirerRequestId = log.one(PaymentEvent.Authorisation).requestId();
                                  return tuple(
                                      new ReversalData(
                                          rollbackType == Cancel || rollbackType == RollbackRequest,
                                          rollbackType != Cancel,
                                          paymentData.amount().requested(),
                                          paymentData.merchantReference(),
                                          acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                          paymentData.simulation()
                                      ),
                                      merchant,
                                      acquirerRequestId
                                  );
                                })
                                    .trigger(GetAcquirerBatchNumber)
                                    .with(d -> new MerchantId(d.t2().id()))
                                    .on(Settlement)
                                    .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id(), CreateIfNotExists))
                                    .trigger(GetBatchNumber)
                                    .with(d -> d.t2().accepted().event().getUnmarshalledData())
                                    .on(Settlement)
                                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                                    .trigger(authorisationToAcquirer.rollbackDispatched())
                                    .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    .on(authorisationToAcquirer)
                                    .identifiedBy(d -> entityId(d.t1().t1().t3()))
                                    //.trigger(authorisationReversalToAcquirer.requestDispatched())
                                    //.with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    //.on(authorisationReversalToAcquirer)
                                    //.identifiedBy(newEntityId())
//                                    .trigger(authorisationReversal())
//                                    .with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
//                                    .to(Acquirer)
//                                    .guaranteed()
//                                    .responseValidator(validateAuthorisationReversalResponse())
                                    .trigger(rolledBackAuthorisationRequestToMerchant.requestDispatched())
                                    .with(d -> tuple(d.t1().t1().t1().t1(), d.t1().t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    .on(rolledBackAuthorisationRequestToMerchant)
                                    .identifiedBy(newEntityId())
//                                    .trigger(rolledBackAuthorisationRequest())
//                                    .with(d -> tuple(d.t1().t1(), d.t2().accepted().event().getUnmarshalledData()))
//                                    .to(Queues.Merchant)
//                                    .guaranteed()
                                    //.complete()
                            )
                            .output(d -> new AcquirerAuthorisation(d.t1().t1().t1().t2(), d.t1().t2().accepted().event().entityId())),
                        Tuple2::t2
                    )
                    .otherwise(
                        onEvent(Preauthorisation).to(ProcessingAuthorisation)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest).t1(),
                                c.log().one(ValidPaymentRequest).t2(),
                                c.input(),
                                tokenDecrypter.apply(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                                c.eventReference()
                            ))
                            .trigger(preauthorisationToAcquirer.requestDispatched()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).on(preauthorisationToAcquirer).identifiedBy(newEntityId())
//                            .trigger(preauthorisation()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).to(Acquirer).responseValidator(validatePreauthorisationResponse())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .reversible(
                                assemble((log, rollbackType) -> {
                                  var paymentData = log.one(ValidPaymentRequest);
                                  AcquirerResponse acquirerResponse = log.lastIfExists(PreauthorisationApproved).orElse(null);
                                  UUID acquirerRequestId = log.one(Preauthorisation).requestId();
                                  return tuple(
                                      new ReversalData(
                                          rollbackType == Cancel || rollbackType == RollbackRequest,
                                          rollbackType != Cancel,
                                          paymentData.t1().amount().requested(),
                                          paymentData.t1().merchantReference(),
                                          acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                          paymentData.t1().simulation()
                                      ),
                                      paymentData.t2(),
                                      acquirerRequestId
                                  );
                                })
                                    .trigger(preauthorisationToAcquirer.rollbackDispatched())
                                    .with(d -> tuple(d.t1(), d.t2()))
                                    .on(preauthorisationToAcquirer)
                                    .identifiedBy(d -> entityId(d.t3()))
                                    .trigger(rolledBackPreauthorisationRequestToMerchant.requestDispatched())
                                    .with(d -> tuple(d.t1().t1(), d.t1().t2()))
                                    .on(rolledBackPreauthorisationRequestToMerchant)
                                    .identifiedBy(newEntityId())
                            )
                            .output(d -> new AcquirerAuthorisation(d.t1().t1().t3(), d.t1().t2().accepted().event().entityId())),
                        Tuple2::t2
                    )
            ),
            ProcessingAuthorisation, List.of(
                onEvent(PreauthorisationApproved).to(Preauthorised)
                    .assemble(((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input)))
                    .trigger(approvedPreauthorisationToMerchant.requestDispatched()).with(d -> d).on(approvedPreauthorisationToMerchant).identifiedBy(newEntityId())
//                    .trigger(approvedPreauthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t3()),
                onEvent(RequestUndelivered).to(AuthorisationFailed)
                    .assemble((_, log) -> tuple(
                        log.one(ValidPaymentRequest).t1(),
                        log.one(ValidPaymentRequest).t2()
                    ))
                    .trigger(failedAuthorisationToMerchant.requestDispatched()).with(d -> d).on(failedAuthorisationToMerchant).identifiedBy(newEntityId())
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
                    .trigger(approvedAuthorisationToMerchant.requestDispatched())
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
                    .trigger(declinedAuthorisationToMerchant.requestDispatched()).with(d -> d).on(declinedAuthorisationToMerchant).identifiedBy(newEntityId())
//                    .trigger(declinedAuthorisation()).with(d -> d).to(Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t3())
            ),
            AuthenticationFailed, List.of(),
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
                    .trigger(approvedCaptureToMerchant.requestDispatched())
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
      AtLeastOnce<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>> captureRequestedTooLateToAcquirer,
      AtLeastOnce<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture>> captureToAcquirer
  ) {
    return onEvent(CaptureRequest).to(ProcessingCapture)
        .assemble(c -> new CaptureRequestData(
            c.log().one(ValidPaymentRequest).t1(),
            c.log().one(ValidPaymentRequest).t2(),
            c.log().one(Preauthorisation).authenticationResult(),
            c.log().one(PreauthorisationApproved),
            c.input(),
            c.log().all(CaptureApproved).stream()
                .map(AcquirerResponse::amount)
                .mapToLong(Long::longValue)
                .sum(),
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
                    c.log().one(PaymentEvent.Authorisation, Preauthorisation).authenticationResult(),
                    c.input(),
                    tokenDecrypter.apply(c.log().one(ValidPaymentRequest).t1().authenticationData()),
                    c.eventReference()
                ))
                .trigger(captureRequestedTooLateToAcquirer.requestDispatched())
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
                .trigger(captureToAcquirer.requestDispatched())
                .with(d -> tuple(
                    d.t1().t1().authorisationData(),
                    d.t1().t1().merchant(),
                    d.t1().t1().authenticationResult(),
                    tokenDecrypter.apply(d.t1().t1().authorisationData().authenticationData()),
                    d.t2().isAccepted() ? d.t2().accepted().event().getUnmarshalledData() : new AcquirerBatchNumber(d.t1().t1().merchant().id(), 1),
                    d.t1().t1().bankResponse(),
                    d.t1().t1().captureData()
                ))
                .on(captureToAcquirer)
                .identifiedBy(newEntityId())
                .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t1().captureData())
        );
  }

  private Map<State, List<TransitionModel<?, ?>>> refundTransitions(
      State anchor,
      int i,
      AtMostOnce<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund>, Tuple3<ReversalData, Merchant, AcquirerBatchNumber>> refundAuthorisationToAcquirer,
      AtLeastOnce<Tuple2<Authorisation, Merchant>> failedRefundToMerchant,
      AtLeastOnce<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>> approvedRefundToMerchant,
      AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse>> declinedRefundToMerchant
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
                .assemble(refundAssembler())
                .when(d -> d.alreadyRefundedAmount() + d.refundData().amount() <= d.alreadyCapturedAmount())
                .then(
                    onEvent(ValidRefundRequest).to(processingState)
                        .assemble(refundAssembler())
                        .trigger(GetAcquirerBatchNumber)
                        .with(d -> new MerchantId(d.merchant().id()))
                        .on(Settlement).identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchant().id()))
                        .trigger(refundAuthorisationToAcquirer.requestDispatched())
                        .with(d -> tuple(d.t1().authorisationData(), d.t1().merchant(), d.t1().authenticationResult(), d.t1().paymentToken(), d.t2().accepted().event().getUnmarshalledData(), d.t1().refundData()))
                        .on(refundAuthorisationToAcquirer)
                        .identifiedBy(newEntityId())
                        .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().eventReference())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .reversible(
                            assemble((log, rollbackType) -> {
                              Tuple2<Authorisation, Merchant> paymentData = log.one(ValidPaymentRequest);
                              AcquirerRefund refundData = log.last(ValidRefundRequest);
                              AcquirerResponse acquirerResponse = log.lastIfExists(RefundApproved).orElse(null);
                              return tuple(
                                  new ReversalData(
                                      rollbackType == Cancel || rollbackType == RollbackRequest,
                                      rollbackType != Cancel,
                                      refundData.refund().amount(),
                                      paymentData.t1().merchantReference(),
                                      acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                      paymentData.t1().simulation()
                                  ),
                                  paymentData.t2(),
                                  refundData.requestId()
                              );
                            })
                                .trigger(GetAcquirerBatchNumber)
                                .with(d -> new MerchantId(d.t2().id()))
                                .on(Settlement)
                                .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id()))
                                .trigger(refundAuthorisationToAcquirer.rollbackDispatched())
                                .with(d -> tuple(d.t1().t1(), d.t1().t2(), d.t2().accepted().event().getUnmarshalledData()))
                                .on(refundAuthorisationToAcquirer)
                                .identifiedBy(d -> entityId(d.t1().t3()))
                        )
                        .output(d -> new AcquirerRefund(d.t1().t1().t1().refundData(), d.t1().t2().accepted().event().entityId())),
                    RefundRequestData::refundData
                )
                .otherwise(
                    onEvent(DeclinedRefund).toSelf()
                        .assemble(TransitionContext::eventReference)
                        .trigger(CompleteInvalidRequest)
                        .with(d -> tuple("Refund amount too large", d))
                        .on(RequestDispatching)
                        .identifiedBy(entityIdFromSession())
                        .output(),
                    RefundRequestData::refundData
                )
        ),
        processingState,
        List.of(
            onEvent(RequestUndelivered).to(anchor)
                .assemble((_, log) -> log.one(ValidPaymentRequest))
                .trigger(failedRefundToMerchant.requestDispatched())
                .with(d -> d)
                .on(failedRefundToMerchant)
                .identifiedBy(newEntityId())
//                .trigger(failedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output(),
            onEvent(RefundApproved).to(anchor)
                .assemble((input, log) -> {
                  Tuple2<Authorisation, Merchant> paymentData = log.one(ValidPaymentRequest);
                  Refund refundData = log.last(ValidRefundRequest).refund();
                  return tuple(paymentData.t1(), paymentData.t2(), input, refundData);
                })
                .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.t2().id(), d.t3().batchNumber())).on(Settlement)
                    .identifiedBy(
                        d -> secondaryId(AcquirerBatchNumber, new AcquirerBatchNumber(d.t2().id(), d.t3().batchNumber()), CreateIfNotExists),
                        d -> lastInIdGroup(BatchNumber, d.t2().id())
                    )
                .trigger(MerchantDebit).with(d -> d.t1().t3().amount()).on(Settlement)
                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                .trigger(approvedRefundToMerchant.requestDispatched())
                .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t1().t3(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t4()))
                .on(approvedRefundToMerchant)
                .identifiedBy(newEntityId())
                .reversible(
                    assemble((log, _) -> tuple(log.last(ValidRefundRequest), log.one(ValidPaymentRequest).t2()))
                        .trigger(MerchantDebitReversed).with(d -> d.t1().refund().amount()).on(Settlement)
                        .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id()))
                )
                .output(d -> d.t1().t1().t1().t3()),
            onEvent(AcquirerDeclined).to(anchor)
                .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                .trigger(declinedRefundToMerchant.requestDispatched())
                .with(d -> d)
                .on(declinedRefundToMerchant)
                .identifiedBy(newEntityId())
//                .trigger(declinedRefund()).with(d -> d).to(Queues.Merchant).guaranteed()
                .output(d -> d.t1().t3())
        )
    );
  }

  private Function<TransitionContext<Refund>, RefundRequestData> refundAssembler() {
    return c -> {
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
          c.log().one(PaymentEvent.Authorisation, Preauthorisation).authenticationResult(),
          tokenDecrypter.apply(authorisationData.authenticationData()),
          c.input(),
          alreadyCapturedAmount,
          alreadyRefundedAmount,
          c.input().simulation(),
          c.eventReference()
      );
    };
  }

}
