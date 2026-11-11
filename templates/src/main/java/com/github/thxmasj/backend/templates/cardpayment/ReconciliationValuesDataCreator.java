package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCredit;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebit;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerResponse.ReconciliationValues;

public class ReconciliationValuesDataCreator {

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
