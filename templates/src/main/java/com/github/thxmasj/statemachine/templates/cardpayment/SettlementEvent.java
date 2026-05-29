package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BasicEventType.ReadOnly;
import com.github.thxmasj.statemachine.EventType;
import java.util.UUID;

public interface SettlementEvent {

  EventType<Long, Long> MerchantCredit =
      BasicEventType.of("MerchantCredit", UUID.fromString("0a238324-c3d9-4297-a695-5cbdbede3fd4"), Long.class);
  EventType<AcquirerBatchNumber, Void> Open =
      BasicEventType.of("Open", UUID.fromString("c7d16033-fdc0-499a-b92c-18e07caf93f2"), AcquirerBatchNumber.class, Void.class);
  EventType<CutOff, CutOff> CutOffRequest =
      BasicEventType.of("CutOffRequest", UUID.fromString("2163fbfa-3870-43cd-8025-ec3fd9689bd2"), CutOff.class);
  EventType<AcquirerResponse, Void> Reconcile =
      BasicEventType.of("Reconcile", UUID.fromString("79bba5bd-d813-43ad-8d3e-5597efe24853"), AcquirerResponse.class, Void.class);
  EventType<AcquirerResponse, Void> InBalance =
      BasicEventType.of("InBalance", UUID.fromString("95a4e74c-e3ec-49a3-976e-28d14f0e41f6"), AcquirerResponse.class, Void.class);
  EventType<AcquirerResponse, Void> OutOfBalance =
      BasicEventType.of("OutOfBalance", UUID.fromString("2ea23e6e-e11b-4fd9-88e6-8a8f18c9c086"), AcquirerResponse.class, Void.class);
  EventType<Void, Void> Timeout =
      BasicEventType.of("Timeout", UUID.fromString("4a4038cd-4120-4512-9850-b9eb8274969c"));
  EventType<Long, Long> MerchantCreditReversed =
      BasicEventType.of("MerchantCreditReversed", UUID.fromString("dfc2b874-bccd-45e4-85ba-db44255c3e0f"), Long.class);
  EventType<Long, Long> MerchantDebit =
      BasicEventType.of("MerchantDebit", UUID.fromString("14147dca-bf25-487a-bee4-fe95521a0bb9"), Long.class);
  EventType<Long, Long> MerchantDebitReversed =
      BasicEventType.of("MerchantDebitReversed", UUID.fromString("9b51bf01-bdaa-4284-a853-bdceab8d8c04"), Long.class);
  EventType<MerchantId, AcquirerBatchNumber> GetAcquirerBatchNumber =
      new ReadOnly<>("GetAcquirerBatchNumber", UUID.fromString("01700fc1-fa15-4142-a673-ba0e9a2768b6"), MerchantId.class, AcquirerBatchNumber.class);
  EventType<AcquirerBatchNumber, BatchNumber> GetBatchNumber =
      BasicEventType.of("GetBatchNumber", UUID.fromString("ad4a9ec8-d0e6-44ce-bef3-d2bd20709005"), AcquirerBatchNumber.class, BatchNumber.class);

  record CutOff(String merchantId, long batchNumber, String merchantAggregatorId, String merchantAggregatorBaseUrl) {}

}
