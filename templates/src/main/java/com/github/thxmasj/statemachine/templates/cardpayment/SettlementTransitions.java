package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.IncomingResponseValidator;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.AcceptedRequest;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.nextInIdGroup;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.CutOffRequestDataCreator.acquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.CutOffRequestDataCreator.batchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Error;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.ProcessingSettlement;
import static com.github.thxmasj.statemachine.templates.cardpayment.PaymentState.Reconciled;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Acquirer;
import static com.github.thxmasj.statemachine.templates.cardpayment.Queues.Merchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.ReconciliationValuesDataCreator.reconciliationValues;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Open;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Reconcile;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Timeout;

public abstract class SettlementTransitions {

  private final InboxExchange inboxExchange;

  public SettlementTransitions(InboxExchange inboxExchange) {this.inboxExchange = inboxExchange;}

  protected abstract IncomingResponseValidator<AcquirerResponse> validateSettlementResponse();

  protected abstract OutgoingRequests.Reconciliation reconciliation();

  protected abstract OutgoingRequests.ApprovedCutOff approvedCutOff();

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            onEvent(Open).toSelf().output(),
            onEvent(MerchantCredit).toSelf()
                .assemble((input, _) -> input.data())
                .output(Function.identity()),
            onEvent(MerchantDebit).toSelf()
                .assemble((input, _) -> input.data())
                .output(Function.identity()),
            onEvent(MerchantCreditReversed).toSelf()
                .assemble((input, _) -> input.data())
                .output(Function.identity()),
            onEvent(MerchantDebitReversed).toSelf()
                .assemble((input, _) -> input.data())
                .output(Function.identity()),
            onEvent(CutOffRequest).to(ProcessingSettlement)
                .assemble((input, log) -> tuple(input.data(), batchNumber(log), acquirerBatchNumber(log)))
                .trigger(reconciliation()).with(d -> tuple(d.t2(), d.t3())).to(Acquirer).guaranteed().responseValidator(validateSettlementResponse())
                .trigger(Open).on(Settlement)
                .identifiedBy(nextInIdGroup(BatchNumber, AlwaysCreate))
                .identifiedBy(nextInIdGroup(AcquirerBatchNumber, AlwaysCreate))
                .trigger(AcceptedRequest).on(inboxExchange).identifiedBy(entityIdFromSession())
                //.trigger(new Created()).with(_ -> "")
                .output(d -> d.t1().t1().t1())
        ),
        ProcessingSettlement, List.of(
            // For previous batch to stay open for ongoing capture exchanges when cut-off is performed
            onEvent(MerchantCredit).toSelf().assemble((input, _) -> input.data()).output(Function.identity()),
            onEvent(MerchantDebit).toSelf().assemble((input, _) -> input.data()).output(Function.identity()),
            onEvent(MerchantCreditReversed).toSelf().assemble((input, _) -> input.data()).output(Function.identity()),
            onEvent(MerchantDebitReversed).toSelf().assemble((input, _) -> input.data()).output(Function.identity()),
            onEvent(Reconcile).to(Reconciled)
                .assemble((input, log) -> tuple(
                    log.one(CutOffRequest),
                    reconciliationValues(log),
                    input.data().reconciliationValues(),
                    input.data()
                ))
                .when(d -> d.t2().equals(d.t3()))
                .then(
                    onEvent(InBalance).to(Reconciled)
                        .assemble((input, log) -> tuple(
                            log.one(CutOffRequest),
                            reconciliationValues(log),
                            input.data().reconciliationValues(),
                            input.data()
                        ))
                        .trigger(approvedCutOff()).with(d -> new Tuple2<>(d.t1(), d.t2())).to(Merchant).guaranteed()
                        .output(),
                    Tuple4::t4
                )
                .when(d -> !d.t2().equals(d.t3()))
                .then(onEvent(OutOfBalance).to(Error).output(), d -> null)
                .output(),
            onEvent(Timeout).to(Error).output()
        ),
        Reconciled, List.of(),
        Error, List.of()
    );
  }

}
