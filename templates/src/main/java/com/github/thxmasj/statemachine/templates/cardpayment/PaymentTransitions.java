package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.lastInIdGroup;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.State.Intermediate;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel.mergeModels;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteInvalidRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ServiceUnavailable;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.atLeastOnce;
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.atMostOnce;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerDeclined;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.AcquirerResponded;
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
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.DelaySpecification;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.Tuples.Tuple5;
import com.github.thxmasj.statemachine.Tuples.Tuple6;
import com.github.thxmasj.statemachine.Tuples.Tuple7;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnceBuilder;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnce;
import com.github.thxmasj.statemachine.http.outbox.AtMostOnceBuilder;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequestContract;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
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
import java.util.function.BiFunction;
import java.util.function.Function;

public class PaymentTransitions {
  
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final Function<String, PaymentToken> tokenDecrypter;
  private final List<EntityModel> outboxRequestModels;
  
  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;  
  }

  public List<EntityModel> outboxRequestModels() {
    return outboxRequestModels;
  }

  public PaymentTransitions(
      Function<String, PaymentToken> tokenDecrypter,
      HttpOutboxRequestContract<AuthenticationData, ?, ?, ?> authentication,
      HttpClient authenticatorClient,

      HttpOutboxRequestContract<Tuple5<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber>, ?, ?, ?> authorisation,
      HttpOutboxRequestContract<Tuple3<ReversalData, Merchant, AcquirerBatchNumber>, ?, ?, ?> authorisationReversal,
      HttpOutboxRequestContract<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>, ?, ?, ?> failedAuthentication,
      HttpOutboxRequestContract<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, ?, ?> preauthorisation,
      HttpOutboxRequestContract<Tuple2<ReversalData, Merchant>, ?, ?, ?> preauthorisationReversal,
      HttpOutboxRequestContract<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>, ?, ?, ?> captureRequestedTooLate,
      HttpOutboxRequestContract<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture>, ?, ?, ?> capture,
      HttpOutboxRequestContract<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund>, ?, ?, ?> refundAuthorisation,
      HttpClient paymentBaltusClient,

      HttpOutboxRequestContract<Tuple3<ReversalData, Merchant, BatchNumber>, ?, ?, ?> rolledBackAuthorisationRequest,
      HttpOutboxRequestContract<Tuple2<ReversalData, Merchant>, ?, ?, ?> rolledBackPreauthorisationRequest,
      HttpOutboxRequestContract<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?, ?, ?> approvedPreauthorisation,
      HttpOutboxRequestContract<Tuple2<Authorisation, Merchant>, ?, ?, ?> failedAuthorisation,
      HttpOutboxRequestContract<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?, ?, ?> approvedAuthorisation,
      HttpOutboxRequestContract<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?, ?, ?> declinedAuthorisation,
      HttpOutboxRequestContract<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?, ?, ?> approvedCapture,
      HttpOutboxRequestContract<Tuple2<Authorisation, Merchant>, ?, ?, ?> failedRefund,
      HttpOutboxRequestContract<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>, ?, ?, ?> approvedRefund,
      HttpOutboxRequestContract<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?, ?, ?> declinedRefund,
      HttpClient merchantClient
  ) {
    this.tokenDecrypter = tokenDecrypter;

    AtMostOnce<AuthenticationData, Void> authenticationToValidator = buildAuthentication(
        authentication,
        authenticatorClient
    );

    AtMostOnce<Tuple5<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber>, Tuple3<ReversalData, Merchant, AcquirerBatchNumber>> authorisationToAcquirer = buildAtMostOnce(
        "Authorisation",
        UUID.fromString("2282e583-8136-4a21-8ac5-138200503659"),
        authorisation,
        paymentBaltusClient,
        AcquirerResponded,
        acquirerAdvice("unused", UUID.randomUUID(), authorisationReversal, paymentBaltusClient, null, List.of("00", "25")),
        2
    );

    AtLeastOnce<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>> failedAuthenticationToAcquirer = nonFinancialAdviceToAcquirer(
        "FailedAuthentication",
        UUID.fromString("1ae0db33-3264-4c11-805c-5edeb8f31f40"),
        failedAuthentication,
        paymentBaltusClient
    );

    AtMostOnce<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, Tuple2<ReversalData, Merchant>> preauthorisationToAcquirer = buildAtMostOnce(
        "Preauthorisation",
        UUID.fromString("cd95f390-8cb3-4fa5-b791-171b87fd22b1"),
        preauthorisation,
        paymentBaltusClient,
        AcquirerResponded,
        acquirerAdvice("unused", UUID.fromString("1282cb9d-eb8c-416f-9ce0-9d936e1f9af9"), preauthorisationReversal, paymentBaltusClient, null, List.of("00", "25")),
        2
    );

    AtLeastOnce<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>> captureRequestedTooLateToAcquirer = nonFinancialAdviceToAcquirer(
        "CaptureRequestedTooLate",
        UUID.fromString("54830ddd-2dbf-4c06-bd6d-fa371f6a3f94"),
        captureRequestedTooLate,
        paymentBaltusClient
    );

    AtLeastOnce<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture>> captureToAcquirer = acquirerAdvice(
        "Capture",
        UUID.fromString("3f339f30-30ba-4d15-b259-0c5cc10167ad"),
        capture,
        paymentBaltusClient,
        CaptureApproved,
        List.of("00", "86")
    );

    AtMostOnce<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund>, Tuple3<ReversalData, Merchant, AcquirerBatchNumber>> refundAuthorisationToAcquirer = buildAtMostOnce(
        "RefundAuthorisation",
        UUID.fromString("81617061-5cab-4305-87cf-e96b8157944e"),
        refundAuthorisation,
        paymentBaltusClient,
        RefundApproved,
        acquirerAdvice("unused", UUID.randomUUID(), authorisationReversal, paymentBaltusClient, null, List.of("00", "25")),
        1
    );

    AtLeastOnce<Tuple3<ReversalData, Merchant, BatchNumber>> rolledBackAuthorisationRequestToMerchant = merchantCallback(
        "RolledBackAuthorisation",
        UUID.fromString("1f0857b0-0308-4512-9aaf-8b0bf1eca6b0"),
        rolledBackAuthorisationRequest,
        merchantClient
    );

    AtLeastOnce<Tuple2<ReversalData, Merchant>> rolledBackPreauthorisationRequestToMerchant = merchantCallback(
        "RolledBackPreauthorisation",
        UUID.fromString("e08ec9d0-aeb1-4f6b-9c19-89d9aaf0ae61"),
        rolledBackPreauthorisationRequest,
        merchantClient
    );

    AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse>> approvedPreauthorisationToMerchant = merchantCallback(
        "ApprovedPreauthorisation",
        UUID.fromString("7fff869f-860d-4023-b745-c0eaea2c7fa7"),
        approvedPreauthorisation,
        merchantClient
    );

    AtLeastOnce<Tuple2<Authorisation, Merchant>> failedAuthorisationToMerchant = merchantCallback(
        "FailedAuthorisation",
        UUID.fromString("1b4acdd6-ba06-43a1-be5a-4a06aff824a4"),
        failedAuthorisation,
        merchantClient
    );

    AtLeastOnce<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>> approvedAuthorisationToMerchant = merchantCallback(
        "ApprovedAuthorisation",
        UUID.fromString("76aa4bc4-836c-4809-8a9d-a2c202719dff"),
        approvedAuthorisation,
        merchantClient
    );

    AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse>> declinedAuthorisationToMerchant = merchantCallback(
        "DeclinedAuthorisation",
        UUID.fromString("e800bdae-5168-47a3-a0c1-0fc9ce887da5"),
        declinedAuthorisation,
        merchantClient
    );

    AtLeastOnce<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>> approvedCaptureToMerchant = merchantCallback(
        "ApprovedCapture",
        UUID.fromString("15340ab4-f9fd-4d84-b39d-16ca92208822"),
        approvedCapture,
        merchantClient
    );

    AtLeastOnce<Tuple2<Authorisation, Merchant>> failedRefundToMerchant = merchantCallback(
        "FailedRefund",
        UUID.fromString("33290023-0297-46cd-8c77-1177776ac787"),
        failedRefund,
        merchantClient
    );

    AtLeastOnce<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>> approvedRefundToMerchant = merchantCallback(
        "ApprovedRefund",
        UUID.fromString("11599dfa-9786-4808-99df-8dc129116acd"),
        approvedRefund,
        merchantClient
    );

    AtLeastOnce<Tuple3<Authorisation, Merchant, AcquirerResponse>> declinedRefundToMerchant = merchantCallback(
        "DeclinedRefund",
        UUID.fromString("40293af3-954d-4731-9cd9-82346bd07c37"),
        declinedRefund,
        merchantClient
    );

    this.outboxRequestModels = List.of(
        authenticationToValidator,
        authorisationToAcquirer,
        failedAuthenticationToAcquirer,
        preauthorisationToAcquirer,
        captureRequestedTooLateToAcquirer,
        captureToAcquirer,
        refundAuthorisationToAcquirer,
        rolledBackAuthorisationRequestToMerchant,
        rolledBackPreauthorisationRequestToMerchant,
        approvedPreauthorisationToMerchant,
        failedAuthorisationToMerchant,
        approvedAuthorisationToMerchant,
        declinedAuthorisationToMerchant,
        approvedCaptureToMerchant,
        failedRefundToMerchant,
        approvedRefundToMerchant,
        declinedRefundToMerchant
    );

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
                            .trigger(authenticationToValidator.requestDispatched()).with(Tuple3::t3).on(authenticationToValidator).identifiedBy(newEntityId())
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
                                  Tuple2<Authorisation, Merchant> paymentData = log.one(ValidPaymentRequest);
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
                onEvent(AcquirerResponded).to(Intermediate)
                    .assemble((input, log) -> tuple(input, log.oneIfExists(Preauthorisation).isPresent()))
                    .when(d -> "00".equals(d.t1().responseCode()) && d.t2())
                    .then(
                        onEvent(PreauthorisationApproved).to(Preauthorised)
                            .assemble(((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input)))
                            .trigger(approvedPreauthorisationToMerchant.requestDispatched()).with(d -> d).on(approvedPreauthorisationToMerchant).identifiedBy(newEntityId())
                            .output(d -> d.t1().t3()),
                        d -> d.t1()
                    )
                    .when(d -> "00".equals(d.t1().responseCode()) && !d.t2())
                    .then(
                        onEvent(AuthorisationApproved).to(Authorised)
                            .assemble((input, log) -> tuple(
                                log.one(ValidPaymentRequest).t1(),
                                log.one(ValidPaymentRequest).t2(),
                                input
                            ))
                            .trigger(GetBatchNumber)
                            .with(d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()))
                            .on(Settlement)
                            .identifiedBy(
                                d -> secondaryId(
                                    AcquirerBatchNumber,
                                    new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()),
                                    CreateIfNotExists
                                )
                            )
                            .trigger(MerchantCredit)
                            .with(d -> d.t1().t1().amount().requested())
                            .on(Settlement)
                            .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                            .trigger(approvedAuthorisationToMerchant.requestDispatched())
                            .with(d -> tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t1().t2().accepted().event().getUnmarshalledData(),
                                d.t1().t1().t3()
                            ))
                            .on(approvedAuthorisationToMerchant)
                            .identifiedBy(newEntityId())
                            .reversible(
                                assemble((log, _) -> log.one(ValidPaymentRequest).t1())
                                    .trigger(MerchantCreditReversed).with(d -> d.amount().requested()).on(Settlement)
                                    .identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchantId()))
                            )
                            .output(d -> d.t1().t1().t1().t3()),
                        d -> d.t1()
                    )
                    .otherwise(
                        onEvent(AcquirerDeclined).to(AuthorisationFailed)
                            .assemble((input, log) -> tuple(log.one(ValidPaymentRequest).t1(), log.one(ValidPaymentRequest).t2(), input))
                            .trigger(declinedAuthorisationToMerchant.requestDispatched()).with(d -> d).on(declinedAuthorisationToMerchant).identifiedBy(newEntityId())
                            .output(d -> d.t1().t3()),
                        d -> d.t1()
                    ),
                onEvent(InvalidResponse /* TODO: This is the default for invalidResponseAndRejected for AtMostOnce. Consider a better name. Perhaps BadRequest or InvalidRequest? */).to(AuthorisationFailed)
                    .assemble((input, log) -> tuple(
                        log.one(ValidPaymentRequest).t1(),
                        log.one(ValidPaymentRequest).t2(),
                        input
                    ))
                    .trigger(failedAuthorisationToMerchant.requestDispatched()).with(d -> tuple(d.t1(), d.t2())).on(failedAuthorisationToMerchant).identifiedBy(newEntityId())
                    .output(d -> d.t1().t3()),
                onEvent(ServiceUnavailable).to(AuthorisationFailed)
                    .assemble((_, log) -> tuple(
                        log.one(ValidPaymentRequest).t1(),
                        log.one(ValidPaymentRequest).t2()
                    ))
                    .trigger(failedAuthorisationToMerchant.requestDispatched()).with(d -> d).on(failedAuthorisationToMerchant).identifiedBy(newEntityId())
                    .output()
            ),
            AuthenticationFailed, List.of(),
            AuthorisationFailed, List.of(),
            Rejected, List.of(),
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
                        .assemble(c -> tuple(c.input(), c.eventReference()))
                        .trigger(CompleteInvalidRequest)
                        .with(d -> tuple("Refund amount too large", d.t2()))
                        .on(RequestDispatching)
                        .identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1()),
                    RefundRequestData::refundData
                )
        ),
        processingState,
        List.of(
            onEvent(InvalidResponse).to(anchor)
                .assemble((input, log) -> tuple(log.one(ValidPaymentRequest), input))
                .trigger(failedRefundToMerchant.requestDispatched())
                .with(d -> d.t1())
                .on(failedRefundToMerchant)
                .identifiedBy(newEntityId())
                .output(d -> d.t1().t2()),
            onEvent(ServiceUnavailable).to(anchor)
                .assemble((_, log) -> log.one(ValidPaymentRequest))
                .trigger(failedRefundToMerchant.requestDispatched())
                .with(d -> d)
                .on(failedRefundToMerchant)
                .identifiedBy(newEntityId())
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

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <I, RQ, RS> AtMostOnce<I, Void> buildAuthentication(
      HttpOutboxRequestContract<I, RQ, RS, ?> authentication,
      HttpClient forwarder
  ) {
    AtMostOnceBuilder.ContentParserStep<I, RQ, RS> parserStep = atMostOnce().name("Authentication")
        .id(UUID.fromString("28f371c6-fdec-4466-a1a6-6ea98bd33900"))
        .requestPayloadType(authentication.requestPayloadType())
        .responsePayloadType(authentication.responsePayloadType())
        .messageCreatorReactive(authentication.messageCreator())
        .forwarder(forwarder)
        .inflightTimeout(ofSeconds(10))
        .processModel(Payment)
        .onPeerUnavailable(AuthenticationUnavailable)
        .onMissingResponse(AuthenticationUnavailable)
        .onInvalidResponseRejection(AuthenticationUnavailable)
        .onInvalidResponseUnknown(AuthenticationUnavailable);

    AtMostOnceBuilder.OnSuccessStep<I, RQ, RS> successStep = applyContentParser(parserStep, authentication);

    AtMostOnceBuilder.OnFailureStep<I, RQ, RS> failureStep = successStep.onSuccess(
        PaymentEvent.Authorisation,
        authentication.responseAdapter() != null ? (Function) authentication.responseAdapter() : _ -> null
    );

    AtMostOnceBuilder.OnValidResponseUnknownStep<I, RQ, RS> validUnknownStep = failureStep.onFailure(
        PaymentEvent.AuthenticationFailed,
        authentication.failureAdapter() != null ? (Function) authentication.failureAdapter() : _ -> null
    );

    return validUnknownStep
        .isAccepted(authentication.isAccepted() != null ? authentication.isAccepted() : _ -> true)
        .isRejected(authentication.isRejected() != null ? authentication.isRejected() : _ -> false)
        .isRejectedByInvalidResponse(_ -> true)
        .build();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <I, RI, RQ, RS, S> AtMostOnce<I, RI> buildAtMostOnce(
      String name,
      UUID id,
      HttpOutboxRequestContract<I, RQ, RS, S> request,
      HttpClient forwarder,
      EventType<AcquirerResponse, AcquirerResponse> callbackEvent,
      AtLeastOnce<RI> rollbackModel,
      int numberOfEventsToRollback
  ) {
    AtMostOnceBuilder.ContentParserStep<I, RQ, RS> parserStep = atMostOnce().name(name)
        .id(id)
        .requestPayloadType(request.requestPayloadType())
        .responsePayloadType(request.responsePayloadType())
        .messageCreatorReactive(request.messageCreator())
        .forwarder(forwarder)
        .inflightTimeout(Duration.ofMillis(6600))
        .processModel(Payment)
        .onMissingResponse(Rollback, d -> new Data(-numberOfEventsToRollback, d.name() + ": No response"))
        .onInvalidResponseUnknown(Rollback, d -> new Data(-numberOfEventsToRollback, d.t2()));

    AtMostOnceBuilder.OnSuccessStep<I, RQ, RS> step = applyContentParser(parserStep, request);

    AtMostOnceBuilder.OnFailureStep<I, RQ, RS> failureStep = step;
    if (callbackEvent != null && request.responseAdapter() != null) {
      failureStep = step.onSuccess(callbackEvent, (Function) request.responseAdapter());
    }

    return failureStep
        .onFailure(ServiceUnavailable)
        .onValidResponseUnknown(Rollback, d -> new Data(-numberOfEventsToRollback, d.reasonPhrase()))
        .isAccepted(request.isAccepted() != null ? request.isAccepted() : _ -> true)
        .isRejected(request.isRejected() != null ? request.isRejected() : _ -> false)
        .isRejectedByInvalidResponse(d -> List.of(400, 404).contains(d.t1().statusCode()))
        .rollbackModel(rollbackModel)
        .build();
  }

  private <T, RQ, RS, S> AtLeastOnce<T> nonFinancialAdviceToAcquirer(
      String name,
      UUID id,
      HttpOutboxRequestContract<T, RQ, RS, S> request,
      HttpClient forwarder
  ) {
    return acquirerAdvice(name, id, request, forwarder, null, List.of("00", "86"));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, RQ, RS, S> AtLeastOnce<T> acquirerAdvice(
      String name,
      UUID id,
      HttpOutboxRequestContract<T, RQ, RS, S> request,
      HttpClient forwarder,
      EventType<AcquirerResponse, AcquirerResponse> callbackEvent,
      List<String> acceptedResponseCodes
  ) {
    AtLeastOnceBuilder.RepeatMessageCreatorStep<T, RQ, RS> builderStep = atLeastOnce().name(name)
        .id(id)
        .requestPayloadType(request.requestPayloadType())
        .responsePayloadType(request.responsePayloadType())
        .messageCreatorReactive(request.messageCreator());

    AtLeastOnceBuilder.ForwarderStep<T, RQ, RS> forwarderStep = builderStep;
    if (request.repeatMessageCreator() != null) {
      forwarderStep = builderStep.repeatMessageCreator((BiFunction) request.repeatMessageCreator());
    }

    AtLeastOnceBuilder.ContentParserStep<T, RQ, RS> parserStep = forwarderStep
        .forwarder(forwarder)
        .inflightTimeout(Duration.ofMillis(6600))
        .processModel(Payment);

    AtLeastOnceBuilder.OnSuccessStep<T, RQ, RS> successStep = applyContentParser(parserStep, request);
    AtLeastOnceBuilder.IsAcceptedStep<T, RQ, RS> builder = successStep;
    if (callbackEvent != null && request.responseAdapter() != null) {
      builder = successStep.onSuccess(
          callbackEvent, (Function) request.responseAdapter()
      );
    }
    return builder
        .isAccepted(request.isAccepted() != null ? request.isAccepted() : _ -> true)
        .isFailureTransient(request.isRejected() != null ? request.isRejected() : _ -> false)
        .isRejectedByInvalidResponse(d -> List.of(400, 404).contains(d.t1().statusCode()))
        .isFailureByInvalidResponseTransient(d -> d.t1().statusCode() >= 500 && d.t1().statusCode() <= 599)
        .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(ofHours(5)) < 0)
        .backoffAlgorithm(c -> new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
        .build();
  }

  private <T> AtLeastOnce<T> merchantCallback(
      String name,
      UUID id,
      HttpOutboxRequestContract<T, ?, ?, ?> request,
      HttpClient forwarder
  ) {
    return buildMerchantCallback(name, id, request, forwarder);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private <T, RQ, RS> AtLeastOnce<T> buildMerchantCallback(
      String name,
      UUID id,
      HttpOutboxRequestContract<T, RQ, RS, ?> request,
      HttpClient forwarder
  ) {
    AtLeastOnceBuilder.RepeatMessageCreatorStep<T, RQ, RS> builderStep = atLeastOnce().name(name)
        .id(id)
        .requestPayloadType(request.requestPayloadType())
        .responsePayloadType(request.responsePayloadType())
        .messageCreatorReactive(request.messageCreator());

    AtLeastOnceBuilder.ForwarderStep<T, RQ, RS> forwarderStep = builderStep;
    if (request.repeatMessageCreator() != null) {
      forwarderStep = builderStep.repeatMessageCreator((BiFunction) request.repeatMessageCreator());
    }

    AtLeastOnceBuilder.ContentParserStep<T, RQ, RS> parserStep = forwarderStep
        .forwarder(forwarder)
        .inflightTimeout(Duration.ofMillis(10000))
        .processModel(Payment);

    return applyContentParser(parserStep, request)
        .isAccepted(request.isAccepted() != null ? request.isAccepted() : _ -> true)
        .isFailureTransient(request.isRejected() != null ? request.isRejected() : _ -> false)
        .isRejectedByInvalidResponse(d -> List.of(400, 404).contains(d.t1().statusCode()))
        .isFailureByInvalidResponseTransient(d -> d.t1().statusCode() >= 500 && d.t1().statusCode() <= 599)
        .isAttemptAvailable(c -> Duration.between(c.enqueueTime(), c.now()).compareTo(ofHours(5)) < 0)
        .backoffAlgorithm(c -> new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5).calculateDelay(c.attemptNumber()))
        .build();
  }

  private static <I, RQ, RS> AtMostOnceBuilder.OnSuccessStep<I, RQ, RS> applyContentParser(
      AtMostOnceBuilder.ContentParserStep<I, RQ, RS> step,
      HttpOutboxRequestContract<I, RQ, RS, ?> request
  ) {
    if (request.contentParser() != null) {
      return step.contentParser(request.contentParser());
    }
    return step.contentParser((Function<HttpResponseMessage, Validated<RS>>) (_ -> Validated.valid(null)));
  }

  private static <T, RQ, RS> AtLeastOnceBuilder.OnSuccessStep<T, RQ, RS> applyContentParser(
      AtLeastOnceBuilder.ContentParserStep<T, RQ, RS> step,
      HttpOutboxRequestContract<T, RQ, RS, ?> request
  ) {
    if (request.contentParser() != null) {
      return step.contentParser(request.contentParser());
    }
    return step.contentParser((Function<HttpResponseMessage, Validated<RS>>) (_ -> Validated.valid(null)));
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
