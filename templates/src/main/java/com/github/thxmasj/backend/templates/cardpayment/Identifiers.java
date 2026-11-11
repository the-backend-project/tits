package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import java.util.UUID;

public interface Identifiers {

  EventType<String, String> MerchantId = BasicEventType.of(
      "MerchantId",
      UUID.fromString("2c1166f0-ad63-4387-ae88-c710eee9c9eb"),
      DataType.string()
  );

  /// Batch numbers of the settlements of a merchant, of which the last one is the current settlement of the merchant
  EventType<BatchNumber, BatchNumber> BatchNumber = BasicEventType.of(
      "BatchNumber",
      UUID.fromString("e0ca999b-0853-43eb-b1a6-f0f2ef6130b5"),
      new BatchNumberDataType()
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

}
