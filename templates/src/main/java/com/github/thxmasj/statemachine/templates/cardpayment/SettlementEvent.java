package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;

public class SettlementEvent {

  public enum Type implements EventType {
    Open(1001),
    CutOffRequest(1002, CutOff.class),
    SettlementApproved(1005, AcquirerResponse.class),
    InBalance(1011, String.class),
    OutOfBalance(1012, String.class),
    Timeout(1013),
    MerchantCredit(1014, Long.class),
    MerchantCreditReversed(1015, Long.class),
    MerchantDebit(1016, Long.class),
    MerchantDebitReversed(1017, Long.class);

    private final int id;
    private final Class<?> dataType;

    Type(int id, Class<?> dataType) {
        this.id = id;
        this.dataType = dataType;
    }

    Type(int id) {
        this.id = id;
        this.dataType = null;
    }

    @Override
    public Class<?> dataType() {
      return dataType;
    }

    @Override
    public int id() {
      return id;
    }

    public record CutOff(String merchantId, long batchNumber, String merchantAggregatorId, String merchantAggregatorBaseUrl) {}
  }

}
