package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.lastInIdGroup;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.State.Intermediate;
import static com.github.thxmasj.statemachine.TransitionModel.mergeModels;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assembleWithContext;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.rollbackOn;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteFailedRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteInvalidRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.InvalidResponse;
import static com.github.thxmasj.statemachine.http.outbox.EventTypes.ServiceUnavailable;
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
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.AssignAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetCurrentBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedAmount.validateAmount;
import static com.github.thxmasj.statemachine.templates.cardpayment.validators.ValidatedTransactionTime.validateTransactionTime;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.Identifiable;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModel;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.Tuples.Tuple5;
import com.github.thxmasj.statemachine.Tuples.Tuple6;
import com.github.thxmasj.statemachine.Tuples.Tuple7;
import com.github.thxmasj.statemachine.Tuples.Tuple8;
import com.github.thxmasj.statemachine.http.outbox.DispatchContext;
import com.github.thxmasj.statemachine.http.outbox.Dispatcher;
import com.github.thxmasj.statemachine.http.outbox.DispatcherWithRollback;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

public class PaymentTransitions {
  
  public enum OutboxRequestModelIds implements Identifiable {
    Authentication(UUID.fromString("b76903b7-3f2d-4b0b-b77a-f36e7856d066")),
    // Acquirer
    Authorisation(UUID.fromString("7fea89d9-7f31-4650-b3ba-78b6ab9f9921")),
    AuthorisationRollbackOnAccepted(UUID.fromString("05d8ca3b-f4c9-4467-93a1-3c891724f152")),
    AuthorisationRollbackOnUnknown(UUID.fromString("c05fcf10-45f7-413f-bcf9-e618c1d9cf65")),
    FailedAuthentication(UUID.fromString("bcd191ec-8f2c-4c83-b4a1-f747f41185ea")),
    Preauthorisation(UUID.fromString("8531ecff-1b5f-4f7f-b7fb-e41e96f6e916")),
    PreauthorisationRollbackOnAccepted(UUID.fromString("da7fc4f5-b384-4fbf-adf1-f2c38dd11af4")),
    PreauthorisationRollbackOnUnknown(UUID.fromString("feb595cb-8381-4068-9659-78fe6298d784")),
    CaptureRequestedTooLate(UUID.fromString("6813cc09-fe41-4c78-baa7-695c21e65f27")),
    Capture(UUID.fromString("316e0d21-bece-4287-905a-b525ee55a1c2")),
    Refund(UUID.fromString("83229476-a644-4bfb-b588-bc0d73353794")),
    RefundRollbackOnAccepted(UUID.fromString("7dbd7353-b175-48a0-ac46-9bcee0d4ff3e")),
    RefundRollbackOnUnknown(UUID.fromString("61e85451-20f5-4ccc-8862-7779df765818")),
    // Merchant
    RolledBackAuthorisation(UUID.fromString("38228972-a018-4ee1-9224-d307e43dab13")),
    RolledBackPreauthorisation(UUID.fromString("39aafdeb-4d5c-42f4-a224-9f2c0dcbb973")),
    ApprovedPreauthorisation(UUID.fromString("e615e8b4-c8dc-4a7f-824f-6aab22a96068")),
    FailedAuthorisation(UUID.fromString("e77b1661-2c7f-4e1e-9f87-f9bbed5310c6")),
    ApprovedAuthorisation(UUID.fromString("f20e893a-c2e3-4eb6-8e8e-0c7f0cc04a3f")),
    DeclinedAuthorisation(UUID.fromString("887b5192-b74e-4bbe-904f-c21d6488c9de")),
    ApprovedCapture(UUID.fromString("b39d6729-c005-4dfe-ba31-2187c10cf426")),
    FailedRefund(UUID.fromString("df361ea8-b5f7-4d85-9488-19392f54683e")),
    ApprovedRefund(UUID.fromString("27b677b6-b717-460f-ac00-f2a27064a675")),
    DeclinedRefund(UUID.fromString("d21a7276-0b92-4e8a-8284-b7fe40fb79a9"))
    ;

    private final UUID id;
    OutboxRequestModelIds(UUID id) {this.id = id;}
    @Override public UUID id() {return id;}
  }
  
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final Function<String, PaymentToken> tokenDecrypter;
  private final List<EntityModel> outboxRequestModels;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions;

  // To acquirer
  private final DispatcherWithRollback<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, Tuple2<ReversalData, Merchant>> preauthorisation;
  private final DispatcherWithRollback<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, ?, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> authorisation;
  private final Dispatcher<Tuple8<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture, UUID>, ?> capture;
  private final DispatcherWithRollback<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund, UUID>, ?, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> refund;
  private final Dispatcher<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>, ?> failedAuthentication;
  private final Dispatcher<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>, ?> captureRequestedTooLate;

  // To merchant
  private final Dispatcher<Tuple3<ReversalData, Merchant, BatchNumber>, ?> rolledBackAuthorisationRequest;
  private final Dispatcher<Tuple2<ReversalData, Merchant>, ?> rolledBackPreauthorisationRequest;
  private final Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?> approvedPreauthorisation;
  private final Dispatcher<Tuple2<Authorisation, Merchant>, ?> failedAuthorisation;
  private final Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?> approvedAuthorisation;
  private final Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?> declinedAuthorisation;
  private final Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?> approvedCapture;
  private final Dispatcher<Tuple2<Authorisation, Merchant>, ?> failedRefund;
  private final Dispatcher<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>, ?> approvedRefund;
  private final Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, ?> declinedRefund;

  public DispatcherWithRollback<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, ?, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> authorisation() {
    return authorisation;
  }

  public DispatcherWithRollback<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, ?, Tuple2<ReversalData, Merchant>> preauthorisation() {
    return preauthorisation;
  }

  public List<HttpOutboxRequest<?>> acquirerOutboxRequestModels() {
    return outboxes(authorisation, preauthorisation, capture, refund, failedAuthentication, captureRequestedTooLate);
  }

  public Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, ?> approvedCapture() {
    return approvedCapture;
  }

  public List<HttpOutboxRequest<?>> merchantRefundRequestModels() {
    return outboxes(approvedRefund, declinedRefund, failedRefund);
  }

  public List<HttpOutboxRequest<?>> merchantOutboxRequestModels() {
    return outboxes(
        rolledBackAuthorisationRequest,
        rolledBackPreauthorisationRequest,
        approvedPreauthorisation,
        failedAuthorisation,
        approvedAuthorisation,
        declinedAuthorisation,
        approvedCapture,
        failedRefund,
        approvedRefund,
        declinedRefund
    );
  }

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;  
  }

  public List<EntityModel> outboxRequestModels() {
    return outboxRequestModels;
  }

  /// The transitions to register for the outbox requests, including their rollback models.
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions() {
    return outboxTransitions;
  }

  /// The input types of the outbox requests are inferred from the dispatchers.
  public <I1, I2, I3, I4, I5, I6, I7, I8, I9, I10, I11, I12, I13, I14, I15, I16, I17> PaymentTransitions(
      Function<String, PaymentToken> tokenDecrypter,
      Dispatcher<AuthenticationData, I1> authentication,
      // To acquirer
      DispatcherWithRollback<Tuple4<Authorisation, Merchant, AuthenticationResult, PaymentToken>, I2, Tuple2<ReversalData, Merchant>> preauthorisation,
      DispatcherWithRollback<Tuple6<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, UUID>, I3, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> authorisation,
      Dispatcher<Tuple8<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture, UUID>, I4> capture,
      DispatcherWithRollback<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund, UUID>, I5, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> refund,
      Dispatcher<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>, I6> captureRequestedTooLate,
      Dispatcher<Tuple4<Authorisation, Merchant, FailedAuthenticationResult, PaymentToken>, I7> failedAuthentication,
      // To merchant
      Dispatcher<Tuple3<ReversalData, Merchant, BatchNumber>, I8> rolledBackAuthorisationRequest,
      Dispatcher<Tuple2<ReversalData, Merchant>, I9> rolledBackPreauthorisationRequest,
      Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, I10> approvedPreauthorisation,
      Dispatcher<Tuple2<Authorisation, Merchant>, I11> failedAuthorisation,
      Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, I12> approvedAuthorisation,
      Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, I13> declinedAuthorisation,
      Dispatcher<Tuple4<Authorisation, Merchant, BatchNumber, AcquirerResponse>, I14> approvedCapture,
      Dispatcher<Tuple2<Authorisation, Merchant>, I15> failedRefund,
      Dispatcher<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>, I16> approvedRefund,
      Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, I17> declinedRefund
  ) {
    this.tokenDecrypter = tokenDecrypter;
    this.authorisation = authorisation;
    this.failedAuthentication = failedAuthentication;
    this.preauthorisation = preauthorisation;
    this.captureRequestedTooLate = captureRequestedTooLate;
    this.capture = capture;
    this.refund = refund;
    this.rolledBackAuthorisationRequest = rolledBackAuthorisationRequest;
    this.rolledBackPreauthorisationRequest = rolledBackPreauthorisationRequest;
    this.approvedPreauthorisation = approvedPreauthorisation;
    this.failedAuthorisation = failedAuthorisation;
    this.approvedAuthorisation = approvedAuthorisation;
    this.declinedAuthorisation = declinedAuthorisation;
    this.approvedCapture = approvedCapture;
    this.failedRefund = failedRefund;
    this.approvedRefund = approvedRefund;
    this.declinedRefund = declinedRefund;

    List<Dispatcher<?, ?>> dispatchers = List.of(
        authentication,
        authorisation,
        failedAuthentication,
        preauthorisation,
        captureRequestedTooLate,
        capture,
        refund,
        rolledBackAuthorisationRequest,
        rolledBackPreauthorisationRequest,
        approvedPreauthorisation,
        failedAuthorisation,
        approvedAuthorisation,
        declinedAuthorisation,
        approvedCapture,
        failedRefund,
        approvedRefund,
        declinedRefund
    );
    this.outboxRequestModels = dispatchers.stream().<EntityModel>map(Dispatcher::outbox).toList();
    this.outboxTransitions = transitions(dispatchers);

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
                            .assemble(c -> tuple(c.input(), DispatchContext.of(c)))
                            .trigger(authentication.requestDispatched()).withReactive(d -> authentication.input(d.t2(), d.t1().t3())).on(authentication.outbox()).identifiedBy(newEntityId())
                            .output(d -> tuple(d.t1().t1().t1(), merchant(d.t1().t1().t2(), d.t1().t1().t1().merchantDetails()))),
                        d -> tuple(d.t1().t1(), d.t2().accepted().event().getUnmarshalledData(), d.t1().t2())
                    )
            ),
            ProcessingAuthentication, List.of(
                onEvent(AuthenticationUnavailable).to(AuthenticationFailed)
                    .assemble(c -> c.eventReference())
                    .trigger(CompleteFailedRequest)
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
                                c.eventReference(),
                                DispatchContext.of(c)
                            ))
                            .trigger(failedAuthentication.requestDispatched()).withReactive(d -> failedAuthentication.input(d.t5(), tuple(d.t1(), d.t2(), d.t3(), tokenDecrypter.apply(d.t1().authenticationData()))))
                            .on(failedAuthentication.outbox())
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
                                c.eventReference(),
                                DispatchContext.of(c)
                            ))
                            .trigger(failedAuthentication.requestDispatched()).withReactive(d -> failedAuthentication.input(d.t6(), tuple(d.t1(), d.t2(), d.t3(), d.t4())))
                            .on(failedAuthentication.outbox())
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
                            .trigger(CompleteFailedRequest)
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
                                c.eventReference(),
                                DispatchContext.of(c)
                            ))
                            .trigger(GetAcquirerBatchNumber)
                            .with(d -> new MerchantId(d.t1().t2().id()))
                            .on(Settlement)
                            .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t1().t2().id(), CreateIfNotExists))
                            .trigger(authorisation.requestDispatched()).withReactive(d -> authorisation.input(d.t1().t4(), tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t1().t2(),
                                tokenDecrypter.apply(d.t1().t1().t1().authenticationData()),
                                d.t2().accepted().event().getUnmarshalledData(),
                                d.t2().accepted().event().entityId()
                            )))
                            .on(authorisation.outbox())
                            .identifiedBy(newEntityId())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t3())).on(RequestDispatching)
                            .identifiedBy(entityIdFromSession())
                            .reversible(
                                assembleWithContext((c, rollbackType) -> {
                                  EventLog log = c.log();
                                  Authorisation paymentData = log.one(ValidPaymentRequest).t1();
                                  PaymentEvent.Merchant merchant = log.one(ValidPaymentRequest).t2();
                                  AcquirerResponse acquirerResponse = log.lastIfExists(AuthorisationApproved).orElse(null);
                                  UUID acquirerRequestId = log.one(PaymentEvent.Authorisation).requestId();
                                  return tuple(
                                      new ReversalData(
                                          log.entityId().value(),
                                          rollbackType == Cancel || rollbackType == RollbackRequest,
                                          rollbackType != Cancel,
                                          paymentData.amount().requested(),
                                          paymentData.merchantReference(),
                                          acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                          paymentData.simulation()
                                      ),
                                      merchant,
                                      acquirerRequestId,
                                      DispatchContext.of(c)
                                  );
                                })
                                    .trigger(GetAcquirerBatchNumber)
                                    .with(d -> new MerchantId(d.t2().id()))
                                    .on(Settlement)
                                    .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id(), CreateIfNotExists))
                                    .trigger(GetCurrentBatchNumber)
                                    .with(d -> new MerchantId(d.t1().t2().id()))
                                    .on(Settlement)
                                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                                    .trigger(authorisation.rollbackDispatched())
                                    .with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t2().accepted().event().entityId()))
                                    .on(authorisation.outbox())
                                    .identifiedBy(d -> entityId(d.t1().t1().t3()))
                                    //.trigger(authorisationReversal.requestDispatched())
                                    //.with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
                                    //.on(authorisationReversal)
                                    //.identifiedBy(newEntityId())
//                                    .trigger(authorisationReversal())
//                                    .with(d -> tuple(d.t1().t1(), d.t1().t2().accepted().event().getUnmarshalledData()))
//                                    .to(Acquirer)
//                                    .guaranteed()
//                                    .responseValidator(validateAuthorisationReversalResponse())
                                    .trigger(rolledBackAuthorisationRequest.requestDispatched()).withReactive(d -> rolledBackAuthorisationRequest.input(d.t1().t1().t1().t4(), tuple(d.t1().t1().t1().t1(), d.t1().t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData())))
                                    .on(rolledBackAuthorisationRequest.outbox())
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
                                c.eventReference(),
                                DispatchContext.of(c)
                            ))
                            .trigger(preauthorisation.requestDispatched()).withReactive(d -> preauthorisation.input(d.t6(), tuple(d.t1(), d.t2(), d.t3(), d.t4()))).on(preauthorisation.outbox()).identifiedBy(newEntityId())
//                            .trigger(preauthorisation()).with(d -> tuple(d.t1(), d.t2(), d.t3(), d.t4())).to(Acquirer).responseValidator(validatePreauthorisationResponse())
                            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t5())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .reversible(
                                assembleWithContext((c, rollbackType) -> {
                                  EventLog log = c.log();
                                  Tuple2<Authorisation, Merchant> paymentData = log.one(ValidPaymentRequest);
                                  AcquirerResponse acquirerResponse = log.lastIfExists(PreauthorisationApproved).orElse(null);
                                  UUID acquirerRequestId = log.one(Preauthorisation).requestId();
                                  return tuple(
                                      new ReversalData(
                                          log.entityId().value(),
                                          rollbackType == Cancel || rollbackType == RollbackRequest,
                                          rollbackType != Cancel,
                                          paymentData.t1().amount().requested(),
                                          paymentData.t1().merchantReference(),
                                          acquirerResponse != null ? acquirerResponse.authorisationCode() : null,
                                          paymentData.t1().simulation()
                                      ),
                                      paymentData.t2(),
                                      acquirerRequestId,
                                      DispatchContext.of(c)
                                  );
                                })
                                    .trigger(preauthorisation.rollbackDispatched())
                                    .with(d -> tuple(d.t1(), d.t2()))
                                    .on(preauthorisation.outbox())
                                    .identifiedBy(d -> entityId(d.t3()))
                                    .trigger(rolledBackPreauthorisationRequest.requestDispatched()).withReactive(d -> rolledBackPreauthorisationRequest.input(d.t1().t4(), tuple(d.t1().t1(), d.t1().t2())))
                                    .on(rolledBackPreauthorisationRequest.outbox())
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
                            .assemble(c -> tuple(c.log().one(ValidPaymentRequest).t1(), c.log().one(ValidPaymentRequest).t2(), c.input(), DispatchContext.of(c)))
                            .trigger(approvedPreauthorisation.requestDispatched()).withReactive(d -> approvedPreauthorisation.input(d.t4(), tuple(d.t1(), d.t2(), d.t3()))).on(approvedPreauthorisation.outbox()).identifiedBy(newEntityId())
                            .output(d -> d.t1().t3()),
                        d -> d.t1()
                    )
                    .when(d -> "00".equals(d.t1().responseCode()) && !d.t2())
                    .then(
                        onEvent(AuthorisationApproved).to(Authorised)
                            .assemble(c -> tuple(
                                c.log().one(ValidPaymentRequest).t1(),
                                c.log().one(ValidPaymentRequest).t2(),
                                c.input(),
                                DispatchContext.of(c)
                            ))
                            .trigger(GetBatchNumber)
                            .with(d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()))
                            .on(Settlement)
                            .identifiedBy(
                                d -> secondaryId(
                                    AcquirerBatchNumber,
                                    new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()),
                                    lastInIdGroup(BatchNumber, d.t1().merchantId(), CreateIfNotExists)
                                )
                            )
                            .trigger(MerchantCredit)
                            .with(d -> d.t1().t1().amount().requested())
                            .on(Settlement)
                            .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                            .trigger(approvedAuthorisation.requestDispatched()).withReactive(d -> approvedAuthorisation.input(d.t1().t1().t4(), tuple(
                                d.t1().t1().t1(),
                                d.t1().t1().t2(),
                                d.t1().t2().accepted().event().getUnmarshalledData(),
                                d.t1().t1().t3()
                            )))
                            .on(approvedAuthorisation.outbox())
                            .identifiedBy(newEntityId())
                            .trigger(AssignAcquirerBatchNumber)
                            .with(d -> new AcquirerBatchNumber(d.t1().t1().t1().t1().merchantId(), d.t1().t1().t1().t3().batchNumber()))
                            .on(Settlement)
                            .identifiedBy(d -> entityId(d.t1().t1().t2().accepted().event().entityId()))
                            .reversible(
                                assemble((log, _) -> log.one(ValidPaymentRequest).t1())
                                    .trigger(MerchantCreditReversed).with(d -> d.amount().requested()).on(Settlement)
                                    .identifiedBy(d -> lastInIdGroup(BatchNumber, d.merchantId()))
                            )
                            .output(d -> d.t1().t1().t1().t1().t3()),
                        d -> d.t1()
                    )
                    .otherwise(
                        onEvent(AcquirerDeclined).to(AuthorisationFailed)
                            .assemble(c -> tuple(c.log().one(ValidPaymentRequest).t1(), c.log().one(ValidPaymentRequest).t2(), c.input(), DispatchContext.of(c)))
                            .trigger(declinedAuthorisation.requestDispatched()).withReactive(d -> declinedAuthorisation.input(d.t4(), tuple(d.t1(), d.t2(), d.t3()))).on(declinedAuthorisation.outbox()).identifiedBy(newEntityId())
                            .output(d -> d.t1().t3()),
                        d -> d.t1()
                    ),
                onEvent(InvalidResponse /* TODO: This is the default for invalidResponseAndRejected for AtMostOnce. Consider a better name. Perhaps BadRequest or InvalidRequest? */).to(AuthorisationFailed)
                    .assemble(c -> tuple(
                        c.log().one(ValidPaymentRequest).t1(),
                        c.log().one(ValidPaymentRequest).t2(),
                        c.input(),
                        DispatchContext.of(c)
                    ))
                    .trigger(failedAuthorisation.requestDispatched()).withReactive(d -> failedAuthorisation.input(d.t4(), tuple(d.t1(), d.t2()))).on(failedAuthorisation.outbox()).identifiedBy(newEntityId())
                    .output(d -> d.t1().t3()),
                onEvent(ServiceUnavailable).to(AuthorisationFailed)
                    .assemble(c -> tuple(
                        c.log().one(ValidPaymentRequest).t1(),
                        c.log().one(ValidPaymentRequest).t2(),
                        DispatchContext.of(c)
                    ))
                    .trigger(failedAuthorisation.requestDispatched()).withReactive(d -> failedAuthorisation.input(d.t3(), tuple(d.t1(), d.t2()))).on(failedAuthorisation.outbox()).identifiedBy(newEntityId())
                    .output()
            ),
            AuthenticationFailed, List.of(),
            AuthorisationFailed, List.of(),
            Rejected, List.of(),
            ProcessingCapture, List.of(
                onEvent(CaptureApproved).to(Authorised)
                    .assemble(c -> tuple(c.log().one(ValidPaymentRequest).t1(), c.log().one(ValidPaymentRequest).t2(), c.input(), DispatchContext.of(c)))
                    .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber())).on(Settlement)
                        .identifiedBy(
                            d -> secondaryId(
                                AcquirerBatchNumber,
                                new AcquirerBatchNumber(d.t1().merchantId(), d.t3().batchNumber()),
                                lastInIdGroup(BatchNumber, d.t1().merchantId(), CreateIfNotExists)
                            ),
                            d -> lastInIdGroup(BatchNumber, d.t1().merchantId(), CreateIfNotExists)
                        )
                    .trigger(MerchantCredit).with(d -> d.t1().t3().amount()).on(Settlement)
                        .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                    .trigger(approvedCapture.requestDispatched()).withReactive(d -> approvedCapture.input(d.t1().t1().t4(), tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3())))
                    .on(approvedCapture.outbox())
                    .identifiedBy(newEntityId())
                    .trigger(AssignAcquirerBatchNumber)
                    .with(d -> new AcquirerBatchNumber(d.t1().t1().t1().t1().merchantId(), d.t1().t1().t1().t3().batchNumber()))
                    .on(Settlement)
                    .identifiedBy(d -> entityId(d.t1().t1().t2().accepted().event().entityId()))
//                    .trigger(approvedCapture()).with(d -> tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t3())).to(
//                        Queues.Merchant).guaranteed()
                    .output(d -> d.t1().t1().t1().t1().t3())
            ),
            Preauthorised, List.of(
                rollbackOn(Cancel),
                captureRequestTransition(captureRequestedTooLate, capture)
            ),
            Authorised, List.of(
                captureRequestTransition(captureRequestedTooLate, capture)
            )
        ),
        refundTransitions(
            Authorised,
            1,
            refund,
            failedRefund,
            approvedRefund,
            declinedRefund
        )
    );
  }

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(List<Dispatcher<?, ?>> dispatchers) {
    var transitions = new LinkedHashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    dispatchers.forEach(d -> transitions.putAll(d.transitions()));
    return transitions;
  }

  private static List<HttpOutboxRequest<?>> outboxes(Dispatcher<?, ?>... dispatchers) {
    return Stream.of(dispatchers).<HttpOutboxRequest<?>>map(Dispatcher::outbox).toList();
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

  private <I1, I2> TransitionModel<?, ?> captureRequestTransition(
      Dispatcher<Tuple5<Authorisation, Merchant, AuthenticationResult, Capture, PaymentToken>, I1> captureRequestedTooLate,
      Dispatcher<Tuple8<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, AcquirerResponse, Capture, UUID>, I2> capture
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
                    c.eventReference(),
                    DispatchContext.of(c)
                ))
                .trigger(captureRequestedTooLate.requestDispatched()).withReactive(d -> captureRequestedTooLate.input(d.t7(), tuple(d.t1(), d.t2(), d.t3(), d.t4(), d.t5())))
                .on(captureRequestedTooLate.outbox())
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
                .assemble(c -> tuple(c.input(), c.eventReference(), DispatchContext.of(c)))
                .trigger(GetAcquirerBatchNumber)
                .with(d -> new MerchantId(d.t1().merchant().id()))
                .on(Settlement).identifiedBy(d -> lastInIdGroup(BatchNumber, d.t1().merchant().id(), CreateIfNotExists))
                .trigger(capture.requestDispatched()).withReactive(d -> capture.input(d.t1().t3(), tuple(
                    d.t1().t1().authorisationData(),
                    d.t1().t1().merchant(),
                    d.t1().t1().authenticationResult(),
                    tokenDecrypter.apply(d.t1().t1().authorisationData().authenticationData()),
                    d.t2().isAccepted() ? d.t2().accepted().event().getUnmarshalledData() : new AcquirerBatchNumber(d.t1().t1().merchant().id(), 1),
                    d.t1().t1().bankResponse(),
                    d.t1().t1().captureData(),
                    d.t2().accepted().event().entityId()
                )))
                .on(capture.outbox())
                .identifiedBy(newEntityId())
                .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t1().captureData())
        );
  }

  private <I1, I2, I3, I4> Map<State, List<TransitionModel<?, ?>>> refundTransitions(
      State anchor,
      int i,
      DispatcherWithRollback<Tuple7<Authorisation, Merchant, AuthenticationResult, PaymentToken, AcquirerBatchNumber, Refund, UUID>, I1, Tuple4<ReversalData, Merchant, AcquirerBatchNumber, UUID>> refundAuthorisationToAcquirer,
      Dispatcher<Tuple2<Authorisation, Merchant>, I2> failedRefund,
      Dispatcher<Tuple5<Authorisation, Merchant, AcquirerResponse, BatchNumber, Refund>, I3> approvedRefund,
      Dispatcher<Tuple3<Authorisation, Merchant, AcquirerResponse>, I4> declinedRefund
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
                        .trigger(refundAuthorisationToAcquirer.requestDispatched()).withReactive(d -> refundAuthorisationToAcquirer.input(d.t1().dispatchContext(), tuple(
                            d.t1().authorisationData(),
                            d.t1().merchant(),
                            d.t1().authenticationResult(),
                            d.t1().paymentToken(),
                            d.t2().accepted().event().getUnmarshalledData(),
                            d.t1().refundData(),
                            d.t2().accepted().event().entityId()
                        )))
                        .on(refundAuthorisationToAcquirer.outbox())
                        .identifiedBy(newEntityId())
                        .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().eventReference())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                        .reversible(
                            assemble((log, rollbackType) -> {
                              Tuple2<Authorisation, Merchant> paymentData = log.one(ValidPaymentRequest);
                              AcquirerRefund refundData = log.last(ValidRefundRequest);
                              AcquirerResponse acquirerResponse = log.lastIfExists(RefundApproved).orElse(null);
                              return tuple(
                                  new ReversalData(
                                      log.entityId().value(),
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
                                .with(d -> tuple(
                                    d.t1().t1(),
                                    d.t1().t2(),
                                    d.t2().accepted().event().getUnmarshalledData(),
                                    d.t2().accepted().event().entityId()
                                ))
                                .on(refundAuthorisationToAcquirer.outbox())
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
                .assemble(c -> tuple(c.log().one(ValidPaymentRequest), c.input(), DispatchContext.of(c)))
                .trigger(failedRefund.requestDispatched()).withReactive(d -> failedRefund.input(d.t3(), d.t1()))
                .on(failedRefund.outbox())
                .identifiedBy(newEntityId())
                .output(d -> d.t1().t2()),
            onEvent(ServiceUnavailable).to(anchor)
                .assemble(c -> tuple(c.log().one(ValidPaymentRequest), DispatchContext.of(c)))
                .trigger(failedRefund.requestDispatched()).withReactive(d -> failedRefund.input(d.t2(), d.t1()))
                .on(failedRefund.outbox())
                .identifiedBy(newEntityId())
                .output(),
            onEvent(RefundApproved).to(anchor)
                .assemble(c -> {
                  Tuple2<Authorisation, Merchant> paymentData = c.log().one(ValidPaymentRequest);
                  Refund refundData = c.log().last(ValidRefundRequest).refund();
                  return tuple(paymentData.t1(), paymentData.t2(), c.input(), refundData, DispatchContext.of(c));
                })
                .trigger(GetBatchNumber).with(d -> new AcquirerBatchNumber(d.t2().id(), d.t3().batchNumber())).on(Settlement)
                    .identifiedBy(
                        d -> secondaryId(
                            AcquirerBatchNumber,
                            new AcquirerBatchNumber(d.t2().id(), d.t3().batchNumber()),
                            lastInIdGroup(BatchNumber, d.t2().id())
                        ),
                        d -> lastInIdGroup(BatchNumber, d.t2().id())
                    )
                .trigger(MerchantDebit).with(d -> d.t1().t3().amount()).on(Settlement)
                    .identifiedBy(d -> entityId(d.t2().accepted().event().entityId()))
                .trigger(approvedRefund.requestDispatched()).withReactive(d -> approvedRefund.input(d.t1().t1().t5(), tuple(d.t1().t1().t1(), d.t1().t1().t2(), d.t1().t1().t3(), d.t1().t2().accepted().event().getUnmarshalledData(), d.t1().t1().t4())))
                .on(approvedRefund.outbox())
                .identifiedBy(newEntityId())
                .trigger(AssignAcquirerBatchNumber)
                .with(d -> new AcquirerBatchNumber(d.t1().t1().t1().t2().id(), d.t1().t1().t1().t3().batchNumber()))
                .on(Settlement)
                .identifiedBy(d -> entityId(d.t1().t1().t2().accepted().event().entityId()))
                .reversible(
                    assemble((log, _) -> tuple(log.last(ValidRefundRequest), log.one(ValidPaymentRequest).t2()))
                        .trigger(MerchantDebitReversed).with(d -> d.t1().refund().amount()).on(Settlement)
                        .identifiedBy(d -> lastInIdGroup(BatchNumber, d.t2().id()))
                )
                .output(d -> d.t1().t1().t1().t1().t3()),
            onEvent(AcquirerDeclined).to(anchor)
                .assemble(c -> tuple(c.log().one(ValidPaymentRequest).t1(), c.log().one(ValidPaymentRequest).t2(), c.input(), DispatchContext.of(c)))
                .trigger(declinedRefund.requestDispatched()).withReactive(d -> declinedRefund.input(d.t4(), tuple(d.t1(), d.t2(), d.t3())))
                .on(declinedRefund.outbox())
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
          c.eventReference(),
          DispatchContext.of(c)
      );
    };
  }

}
