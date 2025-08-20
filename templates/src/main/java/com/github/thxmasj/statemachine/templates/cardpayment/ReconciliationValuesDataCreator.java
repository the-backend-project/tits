package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;
import reactor.core.publisher.Mono;

public class ReconciliationValuesDataCreator
    implements DataCreator<AcquirerResponse, Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse>> {

  @Override
  public Mono<Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse>> execute(
      InputEvent<AcquirerResponse> inputEvent,
      EventLog eventLog
  ) {
    return Mono.just(tuple(
        eventLog.one(CutOffRequest).getUnmarshalledData(),
        new ReconciliationValues(
            eventLog.all(MerchantDebit).stream().map(Event::getUnmarshalledData).mapToLong(Long::longValue).sum(),
            (long) eventLog.all(MerchantDebit).size(),
            eventLog.all(MerchantCredit).stream().map(Event::getUnmarshalledData).mapToLong(Long::longValue).sum(),
            (long) eventLog.all(MerchantCredit).size(),
            eventLog.all(MerchantCreditReversed)
                .stream()
                .map(Event::getUnmarshalledData)
                .mapToLong(Long::longValue)
                .sum(),
            (long) eventLog.all(MerchantCreditReversed).size(),
            eventLog.all(MerchantDebitReversed)
                .stream()
                .map(Event::getUnmarshalledData)
                .mapToLong(Long::longValue)
                .sum(),
            (long) eventLog.all(MerchantDebitReversed).size()
        ),
        inputEvent.data().reconciliationValues(),
        inputEvent.data()
    ));
  }
}
