package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;

public class SettlementEvent {

  public enum Type implements EventType {
    Open(1034),
    CutOffRequest(1035, CutOff.class),
    SettlementApproved(1036, AcquirerResponse.class),
    InBalance(1037, String.class),
    OutOfBalance(1038, String.class),
    Timeout(1039),
    MerchantCredit(1040, Long.class),
    MerchantCreditReversed(1041, Long.class),
    MerchantDebit(1042, Long.class),
    MerchantDebitReversed(1043, Long.class);

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
