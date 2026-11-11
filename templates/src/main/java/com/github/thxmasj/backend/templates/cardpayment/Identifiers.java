package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
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

  /// Ids of merchants and settlements
  IdEntityModel Ids = IdEntityModel.of(
      "CardPaymentIds",
      UUID.fromString("5f0d2b8e-4c6a-4e1f-9b3d-7a2c8e4f6b10"),
      MerchantId,
      BatchNumber,
      AcquirerBatchNumber
  );

  /// Settlements of a merchant, by batch number grouped by merchant id. The last one is the current settlement of the
  /// merchant.
  EventType<BatchNumber, BatchNumber> Batch = BasicEventType.of(
      "Batch",
      UUID.fromString("a4e6c8f0-2b3d-4f5a-8c7e-9d1b3f5a7c92"),
      new BatchNumberDataType()
  );

  IndexEntityModel Index = IndexEntityModel.of(
      "CardPaymentIndex",
      UUID.fromString("c7b9d1f3-5e6a-4b8c-a0d2-e4f6a8b0c2d5"),
      Batch
  );

}
