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
import static com.github.thxmasj.backend.http.inbox.HttpInbox.CompleteRequest;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.outbox.HttpOutboxRequest.BatchReferenced;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.AwaitingAcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Open;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.backend.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.backend.templates.cardpayment.ReconciliationValuesDataCreator.reconciliationValues;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.AssignAcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.GetAcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.GetBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.GetCurrentBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.InBalance;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.OpenInitial;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.Reconcile;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.Timeout;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
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
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(BatchNumber).with(d -> d.t1().batchNumber()).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .trigger(AcquirerBatchNumber).with(d -> d.t1().t1().acquirerBatchNumber()).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                .output(d -> d.t1().t1().t1()),
            // The first settlement of a merchant. The acquirer's batch number is assigned when it is first seen in a response.
            onEvent(OpenInitial).to(AwaitingAcquirerBatchNumber)
                .assemble(c -> tuple(new BatchNumber(c.input().value(), 1), c.log().entityId()))
                .trigger(BatchNumber).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .output(d -> d.t1().t1())
        ),
        // Open, but the acquirer's batch number is not known until the first response referring to the settlement
        AwaitingAcquirerBatchNumber, join(List.of(
            onEvent(AssignAcquirerBatchNumber).to(Open)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(AcquirerBatchNumber).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .output(d -> d.t1().t1()),
            onEvent(GetBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            // Number 0 means unknown
            onEvent(GetAcquirerBatchNumber).toSelf()
                .assemble(c -> new AcquirerBatchNumber(c.input().value(), 0))
                .output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        ), openTransitions(reconciliationToAcquirer)),
        Open, join(List.of(
            onEvent(AssignAcquirerBatchNumber).toSelf().assembleInput().output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(GetAcquirerBatchNumber).toSelf().assemble(d -> acquirerBatchNumber(d.log()).orElseThrow()).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        ), openTransitions(reconciliationToAcquirer)),
        ProcessingSettlement, List.of(
            onEvent(AssignAcquirerBatchNumber).toSelf().assembleInput().output(),
            onEvent(GetBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
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
                        .assemble(c -> tuple(
                            c.log().one(CutOffRequest),
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
            onEvent(GetBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d),
            onEvent(GetCurrentBatchNumber).toSelf().assemble(c -> batchNumber(c.log())).output(d -> d)
        )
    );
  }

  /// The batch number assigned when the settlement was opened
  private static BatchNumber batchNumber(EventLog log) {
    return log.oneIfExists(OpenInitial).orElseGet(() -> log.one(SettlementEvent.Open).batchNumber());
  }

  /// The acquirer's batch number, assigned when the settlement was opened or when first seen in a response
  private static Optional<AcquirerBatchNumber> acquirerBatchNumber(EventLog log) {
    return log.oneIfExists(SettlementEvent.Open).map(Opened::acquirerBatchNumber)
        // Assigning it when it is already known records nothing
        .or(() -> log.all(AssignAcquirerBatchNumber).stream().filter(Objects::nonNull).findFirst());
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
        onEvent(MerchantCredit).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebit).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input).output(Function.identity()),
        onEvent(CutOffRequest).to(ProcessingSettlement)
            .assemble(c -> tuple(
                c.input(),
                batchNumber(c.log()),
                acquirerBatchNumber(c.log()).orElseGet(() -> new AcquirerBatchNumber(c.input().merchantId(), 0)),
                c.eventReference(),
                c.log().entityId().value(),
                DispatchContext.of(c)
            ))
            .trigger(Get).on(Aggregate.Merchant).identifiedBy(d -> idEvent(MerchantId, d.t1().merchantId()))
            .trigger(reconciliation.requestDispatched())
            // A rolled back request is settled too, as its reversal is a request referencing the batch on its own
            .when(BatchReferenced, d -> d.t1().t5()).is(HttpOutboxRequest.requestAccepted(), HttpOutboxRequest.rollbackDispatched())
            .withReactive(d -> reconciliation.input(d.t1().t6(), tuple(d.t1().t2(), d.t1().t3(), d.t2().accepted().event().getUnmarshalledData())))
            .on(reconciliation.outbox())
            .identifiedBy(newEntityId())
            .trigger(SettlementEvent.Open).with(d -> new Opened(d.t1().t2().next(), d.t1().t3().next())).on(Settlement).identifiedBy(newEntityId())
            .trigger(CompleteRequest).with(d -> tuple("", d.t1().t1().t4())).on(RequestDispatching).identifiedBy(entityIdFromSession())
            .output(d -> d.t1().t1().t1().t1())
    );
  }

}
