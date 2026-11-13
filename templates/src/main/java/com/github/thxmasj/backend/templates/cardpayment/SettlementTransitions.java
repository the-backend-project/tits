package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.State.Intermediate;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchReferenced;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.AcquirerBatchNumberClaimed;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.BatchNumberClaimed;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Index;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.MerchantIdClaimed;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.BatchIndexed;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantLookedUp;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.AwaitingAcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Open;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.backend.templates.cardpayment.ReconciliationValuesDataCreator.reconciliationValues;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.AcquirerBatchNumberAssigned;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CutOffRequested;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.AcquirerBatchNumberLookedUp;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.BatchNumberLookedUp;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CurrentBatchNumberLookedUp;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.BalanceConfirmed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCredited;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebited;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.InitialSettlementOpened;
import com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.ImbalanceDetected;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.ReconciliationRequested;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.SettlementTimedOut;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import com.github.thxmasj.backend.http.outbox.DispatchContext;
import com.github.thxmasj.backend.http.outbox.Dispatcher;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CutOff;
import com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.Opened;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions;
  private final Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, ?> reconciliation;
  private final Dispatcher<Tuple2<CutOff, ReconciliationValues>, ?> approvedCutOff;

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return transitions;
  }

  public List<HttpOutboxRequest<?>> acquirerOutboxRequestModels() {
    return List.of(reconciliation.outbox());
  }

  public Dispatcher<Tuple2<CutOff, ReconciliationValues>, ?> approvedCutOff() {
    return approvedCutOff;
  }

  public List<EntityModel> outboxRequestModels() {
    return outboxRequestModels;
  }

  /// The transitions to register for the outbox requests.
  public Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> outboxTransitions() {
    return outboxTransitions;
  }

  /// The input types of the outbox requests are inferred from the dispatchers.
  public <I1, I2> SettlementTransitions(
      // validateSettlementResponse()
      Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, I1> reconciliationToAcquirer,
      Dispatcher<Tuple2<CutOff, ReconciliationValues>, I2> approvedCutOffToMerchant
  ) {
    this.reconciliation = reconciliationToAcquirer;
    this.approvedCutOff = approvedCutOffToMerchant;
    this.outboxRequestModels = List.of(
        reconciliationToAcquirer.outbox(),
        approvedCutOffToMerchant.outbox()
    );
    this.outboxTransitions = PaymentTransitions.transitions(List.of(reconciliationToAcquirer, approvedCutOffToMerchant));

    this.transitions = Map.of(
        Begin, List.of(
            // TODO: BatchNumberLookedUp cannot be ReadOnly as it triggers a transition
//            onEvent(BatchNumberLookedUp).to(Open)
//                .assembleInput()
//                .newIdentifier(AcquirerBatchNumber, d -> d)
//                .newIdentifierInGroup(BatchNumber, d -> d.t1().merchantId())
//                .output(d -> d.t2().accepted().id().data()),
//            onEvent(AcquirerBatchNumberLookedUp).toSelf()
//                .assemble(d -> new AcquirerBatchNumber(d.input().value(), 0))
//                .output(d -> d),
            onEvent(SettlementEvent.SettlementOpened).to(Open)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(BatchNumberClaimed).with(d -> d.t1().batchNumber()).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(AcquirerBatchNumberClaimed).with(d -> d.t1().t1().acquirerBatchNumber()).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                .trigger(BatchIndexed).with(d -> d.t1().t1().t1().batchNumber()).on(Index).identifiedBy(d -> newEntityId(d.t1().t1().t2()))
                .output(d -> d.t1().t1().t1().t1()),
            // The first settlement of a merchant. The acquirer's batch number is assigned when it is first seen in a response.
            onEvent(InitialSettlementOpened).to(AwaitingAcquirerBatchNumber)
                .assemble(c -> tuple(new BatchNumber(c.input().value(), 1), c.log().entityId()))
                .trigger(BatchNumberClaimed).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(BatchIndexed).with(d -> d.t1().t1()).on(Index).identifiedBy(d -> newEntityId(d.t1().t2()))
                .output(d -> d.t1().t1().t1())
        ),
        // Open, but the acquirer's batch number is not known until the first response referring to the settlement
        AwaitingAcquirerBatchNumber, join(List.of(
            onEvent(AcquirerBatchNumberAssigned).to(Open)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(AcquirerBatchNumberClaimed).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .output(d -> d.t1().t1()),
            onEvent(BatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            // Number 0 means unknown
            onEvent(AcquirerBatchNumberLookedUp).toSelf()
                .assemble(c -> new AcquirerBatchNumber(c.input().value(), 0))
                .output(d -> d),
            onEvent(CurrentBatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        ), openTransitions(reconciliationToAcquirer)),
        Open, join(List.of(
            onEvent(AcquirerBatchNumberAssigned).toSelf().assembleInput().output(),
            onEvent(BatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(AcquirerBatchNumberLookedUp).toSelf().assemble(d -> acquirerBatchNumber(d.log()).orElseThrow()).output(d -> d),
            onEvent(CurrentBatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        ), openTransitions(reconciliationToAcquirer)),
        ProcessingSettlement, List.of(
            onEvent(AcquirerBatchNumberAssigned).toSelf().assembleInput().output(),
            onEvent(BatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(CurrentBatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            // For previous batch to stay open for ongoing capture exchanges when cut-off is performed
            onEvent(MerchantCredited).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebited).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
            onEvent(ReconciliationRequested).to(Intermediate)
                .assemble((input, log) -> tuple(
                    log.one(CutOffRequested),
                    reconciliationValues(log),
                    input.reconciliationValues(),
                    input
                ))
                .when(d -> d.t2().equals(d.t3())).then(
                    onEvent(BalanceConfirmed).to(Reconciled)
                        .assemble(c -> tuple(
                            c.log().one(CutOffRequested),
                            reconciliationValues(c.log()),
                            c.input().reconciliationValues(),
                            c.input(),
                            DispatchContext.of(c)
                        ))
                        .trigger(approvedCutOffToMerchant.requestDispatched())
                        .withReactive(d -> approvedCutOffToMerchant.input(d.t5(), new Tuple2<>(d.t1(), d.t2())))
                        .on(approvedCutOffToMerchant.outbox())
                        .identifiedBy(newEntityId())
//                        .trigger(approvedCutOff())
//                        .with(d -> new Tuple2<>(d.t1(), d.t2()))
//                        .to(Merchant).guaranteed()
                        .output(),
                    Tuple4::t4
                )
                .otherwise(
                    onEvent(ImbalanceDetected).to(Error)
                        .assembleInput()
                        .output(d -> d),
                    d -> new OutOfBalance(d.t2(), d.t3())
                ),
            onEvent(SettlementTimedOut).to(Error).output()
        ),
        Reconciled, List.of(),
        Error, List.of(
            onEvent(AcquirerBatchNumberAssigned).toSelf().assembleInput().output(),
            onEvent(BatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(CurrentBatchNumberLookedUp).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        )
    );
  }

  /// The batch number assigned when the settlement was opened
  private static BatchNumber batchNumber(EventLog log) {
    return log.oneIfExists(InitialSettlementOpened).orElseGet(() -> log.one(SettlementEvent.SettlementOpened).batchNumber());
  }

  /// The acquirer's batch number, assigned when the settlement was opened or when first seen in a response
  private static Optional<AcquirerBatchNumber> acquirerBatchNumber(EventLog log) {
    return log.oneIfExists(SettlementEvent.SettlementOpened).map(Opened::acquirerBatchNumber)
        // Assigning it when it is already known records nothing
        .or(() -> log.all(AcquirerBatchNumberAssigned).stream().filter(Objects::nonNull).findFirst());
  }

  private static List<TransitionModel<?, ?>> join(List<TransitionModel<?, ?>> first, List<TransitionModel<?, ?>> second) {
    var all = new ArrayList<TransitionModel<?, ?>>(first);
    all.addAll(second);
    return all;
  }

  /// Transitions of an open settlement, regardless of whether the acquirer's batch number is known
  private <I1> List<TransitionModel<?, ?>> openTransitions(
      Dispatcher<Tuple3<BatchNumber, AcquirerBatchNumber, Merchant>, I1> reconciliation
  ) {
    return List.of(
        onEvent(MerchantCredited).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebited).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(CutOffRequested).to(ProcessingSettlement)
            .assemble(c -> tuple(
                c.input(),
                batchNumber(c.log()),
                acquirerBatchNumber(c.log()).orElseGet(() -> new AcquirerBatchNumber(c.input().merchantId(), 0)),
                c.eventReference(),
                c.log().entityId(),
                DispatchContext.of(c)
            ))
            .trigger(MerchantLookedUp).on(Aggregate.Merchant).identifiedBy(d -> idEvent(MerchantIdClaimed, d.t1().merchantId()))
            .trigger(reconciliation.requestDispatched())
            // A rolled back request is settled too, as its reversal is a request referencing the batch on its own
            .when(BatchReferenced, d -> d.t1().t5()).is(HttpOutboxRequest.requestAccepted(), HttpOutboxRequest.rollbackDispatched())
            .withReactive(d -> reconciliation.input(d.t1().t6(), tuple(d.t1().t2(), d.t1().t3(), d.t2().accepted().event().getUnmarshalledData())))
            .on(reconciliation.outbox())
            .identifiedBy(newEntityId())
            .trigger(SettlementEvent.SettlementOpened).with(d -> new Opened(d.t1().t2().next(), d.t1().t3().next())).on(Settlement).identifiedBy(newEntityId())
            .trigger(RequestCompleted).with(d -> tuple("", d.t1().t1().t4())).on(RequestDispatching).identifiedBy(entityIdFromSession())
            .output(d -> d.t1().t1().t1().t1())
    );
  }

}
