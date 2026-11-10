package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.templates.cardpayment.AcquirerResponse.ReconciliationValues;
import java.util.UUID;

public interface SettlementEvent {

  EventType<Long, Long> MerchantCredit =
      BasicEventType.of("MerchantCredit", UUID.fromString("0a238324-c3d9-4297-a695-5cbdbede3fd4"), DataType.json(Long.class));
  EventType<AcquirerBatchNumber, Void> Open =
      BasicEventType.of("Open", UUID.fromString("c7d16033-fdc0-499a-b92c-18e07caf93f2"), DataType.json(AcquirerBatchNumber.class), DataType.none());
  /// Opens the first settlement of a merchant. The acquirer's batch number is not known until the first response.
  EventType<MerchantId, Void> OpenInitial =
      BasicEventType.of("OpenInitial", UUID.fromString("5b0c7f0e-0f6b-4d0e-9a0f-3f2c1c6e8d21"), DataType.json(MerchantId.class), DataType.none());
  /// Assigns the acquirer's batch number to a settlement which is awaiting it. Has no effect in other states.
  EventType<AcquirerBatchNumber, Void> AssignAcquirerBatchNumber =
      BasicEventType.of("AssignAcquirerBatchNumber", UUID.fromString("7d3f1b6e-4a52-4c0e-b1d8-5e9a2c4f6a10"), DataType.json(AcquirerBatchNumber.class), DataType.none());
  EventType<CutOff, CutOff> CutOffRequest =
      BasicEventType.of("CutOffRequest", UUID.fromString("2163fbfa-3870-43cd-8025-ec3fd9689bd2"), DataType.json(CutOff.class));
  EventType<AcquirerResponse, Void> Reconcile =
      BasicEventType.of("Reconcile", UUID.fromString("79bba5bd-d813-43ad-8d3e-5597efe24853"), DataType.json(AcquirerResponse.class), DataType.none());
  EventType<AcquirerResponse, Void> InBalance =
      BasicEventType.of("InBalance", UUID.fromString("95a4e74c-e3ec-49a3-976e-28d14f0e41f6"), DataType.json(AcquirerResponse.class), DataType.none());
  EventType<OutOfBalance, OutOfBalance> OutOfBalance =
      BasicEventType.of("OutOfBalance", UUID.fromString("2ea23e6e-e11b-4fd9-88e6-8a8f18c9c086"), DataType.json(OutOfBalance.class));
  EventType<Void, Void> Timeout =
      BasicEventType.of("Timeout", UUID.fromString("4a4038cd-4120-4512-9850-b9eb8274969c"));
  EventType<Long, Long> MerchantCreditReversed =
      BasicEventType.of("MerchantCreditReversed", UUID.fromString("dfc2b874-bccd-45e4-85ba-db44255c3e0f"), DataType.json(Long.class));
  EventType<Long, Long> MerchantDebit =
      BasicEventType.of("MerchantDebit", UUID.fromString("14147dca-bf25-487a-bee4-fe95521a0bb9"), DataType.json(Long.class));
  EventType<Long, Long> MerchantDebitReversed =
      BasicEventType.of("MerchantDebitReversed", UUID.fromString("9b51bf01-bdaa-4284-a853-bdceab8d8c04"), DataType.json(Long.class));
  EventType<MerchantId, AcquirerBatchNumber> GetAcquirerBatchNumber =
      new ReadOnly<>("GetAcquirerBatchNumber", UUID.fromString("01700fc1-fa15-4142-a673-ba0e9a2768b6"), DataType.json(MerchantId.class), DataType.json(AcquirerBatchNumber.class));
  /// Looks up the batch number of a settlement without assigning the acquirer's batch number to it.
  EventType<MerchantId, BatchNumber> GetCurrentBatchNumber =
      new ReadOnly<>("GetCurrentBatchNumber", UUID.fromString("e2f3a0a4-6c1d-4a58-8f57-2b6f9c1d7a35"), DataType.json(MerchantId.class), DataType.json(BatchNumber.class));
  /// Looks up the batch number of a settlement.
  EventType<AcquirerBatchNumber, BatchNumber> GetBatchNumber =
      new ReadOnly<>("GetBatchNumber", UUID.fromString("ad4a9ec8-d0e6-44ce-bef3-d2bd20709005"), DataType.json(AcquirerBatchNumber.class), DataType.json(BatchNumber.class));

  record CutOff(String merchantId, long batchNumber, String merchantAggregatorId, String merchantAggregatorBaseUrl) {}
  record OutOfBalance(ReconciliationValues ours, ReconciliationValues theirs) {}

}
