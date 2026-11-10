package com.github.thxmasj.backend.templates.cardpayment;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.SecondaryIdModel;
import java.util.UUID;

public interface Identifiers {

  SecondaryIdModel<String> MerchantId = SecondaryIdModel.of(
      "MerchantId",
      UUID.fromString("2c1166f0-ad63-4387-ae88-c710eee9c9eb"),
      DataType.string()
  );

  SecondaryIdModel<BatchNumber> BatchNumber = SecondaryIdModel.of(
      "BatchNumber",
      UUID.fromString("e0ca999b-0853-43eb-b1a6-f0f2ef6130b5"),
      DataType.json(BatchNumber.class),
      IdentifierGroups.Batch
  );

  SecondaryIdModel<AcquirerBatchNumber> AcquirerBatchNumber = SecondaryIdModel.of(
      "AcquirerBatchNumber",
      UUID.fromString("8708bc15-006c-4476-8811-5d5e2511bcd3"),
      DataType.json(AcquirerBatchNumber.class),
      IdentifierGroups.AcquirerBatch
  );
}
