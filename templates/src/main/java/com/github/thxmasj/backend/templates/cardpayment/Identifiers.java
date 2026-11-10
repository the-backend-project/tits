package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IndexEntityModel;
import java.util.UUID;

public interface Identifiers {

  EventType<String, String> MerchantId = BasicEventType.of(
      "MerchantId",
      UUID.fromString("2c1166f0-ad63-4387-ae88-c710eee9c9eb"),
      DataType.string()
  );

  EventType<BatchNumber, BatchNumber> BatchNumber = BasicEventType.of(
      "BatchNumber",
      UUID.fromString("e0ca999b-0853-43eb-b1a6-f0f2ef6130b5"),
      DataType.json(BatchNumber.class)
  );

  EventType<AcquirerBatchNumber, AcquirerBatchNumber> AcquirerBatchNumber = BasicEventType.of(
      "AcquirerBatchNumber",
      UUID.fromString("8708bc15-006c-4476-8811-5d5e2511bcd3"),
      DataType.json(AcquirerBatchNumber.class)
  );

  /// Settlements of a merchant, by merchant id. The last one is the current settlement of the merchant.
  EventType<String, String> SettlementOfMerchant = BasicEventType.of(
      "SettlementOfMerchant",
      UUID.fromString("a4e6c8f0-2b3d-4f5a-8c7e-9d1b3f5a7c92"),
      DataType.string()
  );

  IndexEntityModel Index = IndexEntityModel.of(
      "CardPaymentIndex",
      UUID.fromString("c7b9d1f3-5e6a-4b8c-a0d2-e4f6a8b0c2d5"),
      SettlementOfMerchant
  );

}
