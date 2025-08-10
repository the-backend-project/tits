package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.EventType;
import java.util.UUID;

public interface SettlementEvent {

  EventType<Long, Long> MerchantCredit =
      EventType.of("MerchantCredit", UUID.fromString("0a238324-c3d9-4297-a695-5cbdbede3fd4"), Long.class);
  EventType<Void, Void> Open =
      EventType.of("Open", UUID.fromString("c7d16033-fdc0-499a-b92c-18e07caf93f2"));
  EventType<CutOff, CutOff> CutOffRequest =
      EventType.of("CutOffRequest", UUID.fromString("2163fbfa-3870-43cd-8025-ec3fd9689bd2"), CutOff.class);
  EventType<AcquirerResponse, AcquirerResponse> InBalance =
      EventType.of("InBalance", UUID.fromString("95a4e74c-e3ec-49a3-976e-28d14f0e41f6"), AcquirerResponse.class);
  EventType<AcquirerResponse, AcquirerResponse> OutOfBalance =
      EventType.of("OutOfBalance", UUID.fromString("2ea23e6e-e11b-4fd9-88e6-8a8f18c9c086"), AcquirerResponse.class);
  EventType<Void, Void> Timeout =
      EventType.of("Timeout", UUID.fromString("4a4038cd-4120-4512-9850-b9eb8274969c"));
  EventType<Long, Long> MerchantCreditReversed =
      EventType.of("MerchantCreditReversed", UUID.fromString("dfc2b874-bccd-45e4-85ba-db44255c3e0f"), Long.class);
  EventType<Long, Long> MerchantDebit =
      EventType.of("MerchantDebit", UUID.fromString("14147dca-bf25-487a-bee4-fe95521a0bb9"), Long.class);
  EventType<Long, Long> MerchantDebitReversed =
      EventType.of("MerchantDebitReversed", UUID.fromString("9b51bf01-bdaa-4284-a853-bdceab8d8c04"), Long.class);

  record CutOff(String merchantId, long batchNumber, String merchantAggregatorId, String merchantAggregatorBaseUrl) {}

}
