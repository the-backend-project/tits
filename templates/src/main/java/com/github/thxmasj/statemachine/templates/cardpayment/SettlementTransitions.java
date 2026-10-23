package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.State.Intermediate;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.statemachine.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Open;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.statemachine.templates.cardpayment.ReconciliationValuesDataCreator.reconciliationValues;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Reconcile;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Timeout;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnce;
import com.github.thxmasj.statemachine.http.outbox.AtLeastOnceBuilder;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class SettlementTransitions {

  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final List<EntityModel> outboxRequestModels;
  private final AtLeastOnce<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>> reconciliation;
  private final AtLeastOnce<Tuple2<CutOff, ReconciliationValues>> approvedCutOff;

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  public List<HttpOutboxRequest<?>> acquirerOutboxRequestModels() {
    return List.of(reconciliation);
  }

  public AtLeastOnce<Tuple2<CutOff, ReconciliationValues>> approvedCutOff() {
    return approvedCutOff;
  }

  public List<EntityModel> outboxRequestModels() {
    return outboxRequestModels;
  }

  public SettlementTransitions(
      // validateSettlementResponse()
      AtLeastOnceBuilder.BuildStep<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>> reconciliationToAcquirer,
      AtLeastOnceBuilder.BuildStep<Tuple2<CutOff, ReconciliationValues>> approvedCutOffToMerchant
  ) {
    AtLeastOnce<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>> reconciliationToAcquirerOutbox = reconciliationToAcquirer.build(
        UUID.fromString("d87e0767-bc18-472d-be55-9003328e1fc1"),
        "Reconciliation"
    );
    this.reconciliation = reconciliationToAcquirerOutbox;
    AtLeastOnce<Tuple2<CutOff, ReconciliationValues>> approvedCutOffToMerchantOutbox = approvedCutOffToMerchant.build(
        UUID.fromString("2245b0a3-2ee9-4361-9c60-4965c71d34c0"),
        "ApprovedCutOff"
    );
    this.approvedCutOff = approvedCutOffToMerchantOutbox;
    this.outboxRequestModels = List.of(
        reconciliationToAcquirerOutbox,
        approvedCutOffToMerchantOutbox
    );

    this.transitions = Map.of(
        Begin, List.of(
            // TODO: GetBatchNumber cannot be ReadOnly as it triggers a transition
            onEvent(GetBatchNumber).to(Open)
                .assembleInput()
                .newIdentifier(AcquirerBatchNumber, d -> d)
                .newIdentifierInGroup(BatchNumber, d -> d.t1().merchantId())
                .output(d -> d.t2().accepted().id().data()),
            onEvent(GetAcquirerBatchNumber).toSelf()
                .assemble(d -> new AcquirerBatchNumber(d.input().value(), 0))
                .output(d -> d),
            onEvent(SettlementEvent.Open).to(Open)
                .assembleInput()
                .newIdentifier(AcquirerBatchNumber, d -> d)
                .newIdentifierInGroup(BatchNumber, d -> d.t1().merchantId())
                .output()
        ),
        Open, List.of(
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            onEvent(GetAcquirerBatchNumber).toSelf().assemble(d -> d.log().id(AcquirerBatchNumber)).output(d -> d),
            onEvent(MerchantCredit).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebit).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(CutOffRequest).to(ProcessingSettlement)
                .assemble(c -> tuple(c.input(), c.log().id(BatchNumber), c.log().id(AcquirerBatchNumber), c.eventReference()))
                .trigger(Get).on(Aggregate.Merchant).identifiedBy(d -> secondaryId(MerchantId, d.t1().merchantId()))
                .trigger(reconciliationToAcquirerOutbox.requestDispatched())
                .with(d -> tuple(d.t1().t2(), d.t1().t3(), d.t2().accepted().event().getUnmarshalledData()))
                .on(reconciliationToAcquirerOutbox)
                .identifiedBy(newEntityId())
//                .trigger(reconciliation())
//                .with(d -> tuple(d.t1().t2(), d.t1().t3(), d.t2().accepted().event().getUnmarshalledData()))
//                .to(Acquirer).guaranteed().responseValidator(validateSettlementResponse())
                .trigger(SettlementEvent.Open).with(d -> d.t1().t1().t3().next()).on(Settlement).identifiedBy(newEntityId())
                .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t1().t4())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1().t1().t1())
        ),
        ProcessingSettlement, List.of(
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            // For previous batch to stay open for ongoing capture exchanges when cut-off is performed
            onEvent(MerchantCredit).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebit).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(Reconcile).to(Intermediate)
                .assemble((input, log) -> tuple(
                    log.one(CutOffRequest),
                    reconciliationValues(log),
                    input.reconciliationValues(),
                    input
                ))
                .when(d -> d.t2().equals(d.t3())).then(
                    onEvent(InBalance).to(Reconciled)
                        .assemble((input, log) -> tuple(
                            log.one(CutOffRequest),
                            reconciliationValues(log),
                            input.reconciliationValues(),
                            input
                        ))
                        .trigger(approvedCutOffToMerchantOutbox.requestDispatched())
                        .with(d -> new Tuple2<>(d.t1(), d.t2()))
                        .on(approvedCutOffToMerchantOutbox)
                        .identifiedBy(newEntityId())
//                        .trigger(approvedCutOff())
//                        .with(d -> new Tuple2<>(d.t1(), d.t2()))
//                        .to(Merchant).guaranteed()
                        .output(),
                    Tuple4::t4
                )
                .otherwise(
                    onEvent(OutOfBalance).to(Error)
                        .assembleInput()
                        .output(d -> d),
                    d -> new OutOfBalance(d.t2(), d.t3())
                ),
            onEvent(Timeout).to(Error).output()
        ),
        Reconciled, List.of(),
        Error, List.of(
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d)
        )
    );
  }

}
