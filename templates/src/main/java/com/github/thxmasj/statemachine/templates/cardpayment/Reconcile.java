package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.all;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.InBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebitReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.OutOfBalance;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.SettlementApproved;

import com.github.thxmasj.statemachine.Action;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import reactor.core.publisher.Mono;

public class Reconcile implements Action<String> {

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        all(MerchantCredit),
        all(MerchantCreditReversed),
        all(MerchantDebit),
        all(MerchantDebitReversed),
        one(SettlementApproved)
    );
  }

  @Override
  public Mono<Event> execute(EntityId entityId, Context<String> context, Input input) {
    return Mono.just(new ReconciliationValues(
            input.all(MerchantDebit).stream().map(e -> e.getUnmarshalledData(Long.class)).mapToLong(Long::longValue).sum(),
            (long) input.all(MerchantDebit).size(),
            input.all(MerchantCredit).stream().map(e -> e.getUnmarshalledData(Long.class)).mapToLong(Long::longValue).sum(),
            (long) input.all(MerchantCredit).size(),
            input.all(MerchantCreditReversed)
                .stream()
                .map(e -> e.getUnmarshalledData(Long.class))
                .mapToLong(Long::longValue)
                .sum(),
            (long) input.all(MerchantCreditReversed).size(),
            input.all(MerchantDebitReversed)
                .stream()
                .map(e -> e.getUnmarshalledData(Long.class))
                .mapToLong(Long::longValue)
                .sum(),
            (long) input.all(MerchantDebitReversed).size()
        ))
        .map(values ->
            input.one(SettlementApproved).getUnmarshalledData(AcquirerResponse.class).reconciliationValues().equals(values)
                ?
                context.output(
                    InBalance,
                    "Acquirer: " + input.one(SettlementApproved)
                        .getUnmarshalledData(AcquirerResponse.class)
                        .reconciliationValues() + '\n' + "System: " + values
                ) :
                context.output(
                    OutOfBalance,
                    "Acquirer: " + input.one(SettlementApproved)
                        .getUnmarshalledData(AcquirerResponse.class)
                        .reconciliationValues() + '\n' + "System: " + values
                )
        );
  }

}
