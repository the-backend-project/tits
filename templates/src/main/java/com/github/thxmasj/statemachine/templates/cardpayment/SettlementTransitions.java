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
import static com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest.BatchReferenced;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.AwaitingAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Open;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.statemachine.templates.cardpayment.ReconciliationValuesDataCreator.reconciliationValues;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.AssignAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.GetCurrentBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.OpenInitial;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Reconcile;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Timeout;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Identifiable;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.http.outbox.Dispatcher;
import com.github.thxmasj.statemachine.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class SettlementTransitions {

  public enum OutboxRequestModelIds implements Identifiable {
    Reconciliation(UUID.fromString("9cd21799-5fe4-4f4b-9a21-44304c5b5318")),
    ApprovedCutOff(UUID.fromString("6a3aad86-e86d-4152-8d32-20fecbdc5427"))
    ;
    private final UUID id;
    OutboxRequestModelIds(UUID id) {this.id = id;}
    @Override public UUID id() {return id;}
  }
  private final Map<State, List<TransitionModel<?, ?>>> transitions;
  private final List<EntityModel> outboxRequestModels;
  private final Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, Void> reconciliation;
  private final Dispatcher<Tuple2<CutOff, ReconciliationValues>, Void> approvedCutOff;

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  public List<HttpOutboxRequest<?>> acquirerOutboxRequestModels() {
    return List.of(reconciliation.outbox());
  }

  public Dispatcher<Tuple2<CutOff, ReconciliationValues>, Void> approvedCutOff() {
    return approvedCutOff;
  }

  public List<EntityModel> outboxRequestModels() {
    return outboxRequestModels;
  }

  public SettlementTransitions(
      // validateSettlementResponse()
      Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, Void> reconciliationToAcquirer,
      Dispatcher<Tuple2<CutOff, ReconciliationValues>, Void> approvedCutOffToMerchant
  ) {
    this.reconciliation = reconciliationToAcquirer;
    this.approvedCutOff = approvedCutOffToMerchant;
    this.outboxRequestModels = List.of(
        reconciliationToAcquirer.outbox(),
        approvedCutOffToMerchant.outbox()
    );

    this.transitions = Map.of(
        Begin, List.of(
            // TODO: GetBatchNumber cannot be ReadOnly as it triggers a transition
//            onEvent(GetBatchNumber).to(Open)
//                .assembleInput()
//                .newIdentifier(AcquirerBatchNumber, d -> d)
//                .newIdentifierInGroup(BatchNumber, d -> d.t1().merchantId())
//                .output(d -> d.t2().accepted().id().data()),
//            onEvent(GetAcquirerBatchNumber).toSelf()
//                .assemble(d -> new AcquirerBatchNumber(d.input().value(), 0))
//                .output(d -> d),
            onEvent(SettlementEvent.Open).to(Open)
                .assembleInput()
                .newIdentifier(AcquirerBatchNumber, d -> d)
                .newIdentifierInGroup(BatchNumber, d -> d.t1().merchantId())
                .output(),
            // The first settlement of a merchant. The acquirer's batch number is assigned when it is first seen in a response.
            onEvent(OpenInitial).to(AwaitingAcquirerBatchNumber)
                .assembleInput()
                .newIdentifierInGroup(BatchNumber, d -> d.value())
                .output()
        ),
        // Open, but the acquirer's batch number is not known until the first response referring to the settlement
        AwaitingAcquirerBatchNumber, join(List.of(
            onEvent(AssignAcquirerBatchNumber).to(Open)
                .assembleInput()
                .newIdentifier(AcquirerBatchNumber, d -> d)
                .output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            // Number 0 means unknown
            onEvent(GetAcquirerBatchNumber).toSelf()
                .assemble(c -> new AcquirerBatchNumber(c.input().value(), 0))
                .output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d)
        ), openTransitions()),
        Open, join(List.of(
            onEvent(AssignAcquirerBatchNumber).toSelf().assembleInput().output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            onEvent(GetAcquirerBatchNumber).toSelf().assemble(d -> d.log().id(AcquirerBatchNumber)).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d)
        ), openTransitions()),
        ProcessingSettlement, List.of(
            onEvent(AssignAcquirerBatchNumber).toSelf().assembleInput().output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
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
                        .trigger(approvedCutOff.requestDispatched())
                        .withReactive((ctx, d) -> approvedCutOff.input(ctx, new Tuple2<>(d.t1(), d.t2())))
                        .on(approvedCutOff.outbox())
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
            onEvent(AssignAcquirerBatchNumber).toSelf().assembleInput().output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> c.log().id(BatchNumber)).output(d -> d)
        )
    );
  }

  private static List<TransitionModel<?, ?>> join(List<TransitionModel<?, ?>> first, List<TransitionModel<?, ?>> second) {
    var all = new ArrayList<TransitionModel<?, ?>>(first);
    all.addAll(second);
    return all;
  }

  /// Transitions of an open settlement, regardless of whether the acquirer's batch number is known
  private List<TransitionModel<?, ?>> openTransitions() {
    return List.of(
        onEvent(MerchantCredit).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebit).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(CutOffRequest).to(ProcessingSettlement)
            .assemble(c -> tuple(
                c.input(),
                c.log().id(BatchNumber),
                c.log().idIfExists(AcquirerBatchNumber).orElseGet(() -> new AcquirerBatchNumber(c.input().merchantId(), 0)),
                c.eventReference(),
                c.log().entityId().value()
            ))
            .trigger(Get).on(Aggregate.Merchant).identifiedBy(d -> secondaryId(MerchantId, d.t1().merchantId()))
            .trigger(reconciliation.requestDispatched())
            // A rolled back request is settled too, as its reversal is a request referencing the batch on its own
            .when(BatchReferenced, d -> d.t1().t5()).is(HttpOutboxRequest.requestAccepted(), HttpOutboxRequest.rollbackDispatched())
            .withReactive((ctx, d) -> reconciliation.input(ctx, tuple(d.t1().t2(), d.t1().t3(), d.t2().accepted().event().getUnmarshalledData())))
            .on(reconciliation.outbox())
            .identifiedBy(newEntityId())
            .trigger(SettlementEvent.Open).with(d -> d.t1().t3().next()).on(Settlement).identifiedBy(newEntityId())
            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t4())).on(RequestDispatching).identifiedBy(entityIdFromSession())
            .output(d -> d.t1().t1().t1().t1())
    );
  }

}
