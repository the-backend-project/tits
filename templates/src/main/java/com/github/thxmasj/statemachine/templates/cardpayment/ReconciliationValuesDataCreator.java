package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.all;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.MerchantDebitReversed;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.Type.CutOff;
import reactor.core.publisher.Mono;

public class ReconciliationValuesDataCreator
    implements DataCreator<AcquirerResponse, Tuple3<CutOff, ReconciliationValues, ReconciliationValues>> {

  @Override
  public final Requirements requirements() {
    return Requirements.of(
        one(CutOffRequest),
        all(MerchantCredit),
        all(MerchantCreditReversed),
        all(MerchantDebit),
        all(MerchantDebitReversed)
    );
  }

  @Override
  public Mono<Tuple3<CutOff, ReconciliationValues, ReconciliationValues>> execute(
      InputEvent<AcquirerResponse> inputEvent,
      Input input
  ) {
    System.out.println("Executing ReconciliationValuesDataCreator with inputEvent: " + inputEvent);
    return Mono.just(tuple(
        input.one(CutOffRequest).getUnmarshalledData(CutOff.class),
        new ReconciliationValues(
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
        ),
        inputEvent.data().reconciliationValues()
    ));
  }
}
