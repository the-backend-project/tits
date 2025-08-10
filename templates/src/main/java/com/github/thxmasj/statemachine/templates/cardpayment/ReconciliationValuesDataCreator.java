package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Requirements.all;
import static com.github.thxmasj.statemachine.Requirements.one;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Input;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Requirements;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import reactor.core.publisher.Mono;

public class ReconciliationValuesDataCreator
    implements DataCreator<AcquirerResponse, Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse>> {

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
  public Mono<Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse>> execute(
      InputEvent<AcquirerResponse> inputEvent,
      Input input
  ) {
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
        inputEvent.data().reconciliationValues(),
        inputEvent.data()
    ));
  }
}
