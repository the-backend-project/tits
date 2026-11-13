package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCredited;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantCreditReversed;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebited;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.MerchantDebitReversed;

import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerResponse.ReconciliationValues;

public class ReconciliationValuesDataCreator {

  public static ReconciliationValues reconciliationValues(EventLog log) {
    return new ReconciliationValues(
        log.all(MerchantDebited).stream().mapToLong(Long::longValue).sum(),
        (long) log.all(MerchantDebited).size(),
        log.all(MerchantCredited).stream().mapToLong(Long::longValue).sum(),
        (long) log.all(MerchantCredited).size(),
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
