package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOffRequest;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.statemachine.DataCreator;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import com.github.thxmasj.statemachine.templates.cardpayment.SettlementEvent.CutOff;

public class ReconciliationValuesDataCreator
    implements
    DataCreator<AcquirerResponse, Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse>> {

  @Override
  public Tuple4<CutOff, ReconciliationValues, ReconciliationValues, AcquirerResponse> execute(
      InputEvent<AcquirerResponse> input,
      EventLog log
  ) {
    return tuple(log.one(CutOffRequest), reconciliationValues(log), input.data().reconciliationValues(), input.data());
  }

  public static ReconciliationValues reconciliationValues(EventLog log) {
    return new ReconciliationValues(
        log.all(MerchantDebit).stream().mapToLong(Long::longValue).sum(),
        (long) log.all(MerchantDebit).size(),
        log.all(MerchantCredit).stream().mapToLong(Long::longValue).sum(),
        (long) log.all(MerchantCredit).size(),
        log.all(MerchantCreditReversed)
            .stream()
            .mapToLong(Long::longValue)
            .sum(),
        (long) log.all(MerchantCreditReversed).size(),
        log.all(MerchantDebitReversed)
            .stream()
            .mapToLong(Long::longValue)
            .sum(),
        (long) log.all(MerchantDebitReversed).size()
    );
  }

}
