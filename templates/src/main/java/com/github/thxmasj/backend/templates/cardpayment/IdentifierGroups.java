package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.BatchNumber;

import com.github.thxmasj.backend.core.SecondaryId;
import com.github.thxmasj.backend.core.SecondaryIdModel;

public interface IdentifierGroups {
  SecondaryIdModel.Group<BatchNumber> Batch = new SecondaryIdModel.Group<>() {
    @Override
    public boolean isInitial(BatchNumber value) {
      return value.number() == 1;
    }

    @Override
    public SecondaryId<BatchNumber> initial(Object group) {
      return new SecondaryId<>(BatchNumber, new BatchNumber((String)group, 1L));
    }

    @Override
    public SecondaryId<BatchNumber> next(SecondaryId<BatchNumber> current) {
      return new SecondaryId<>(BatchNumber, new BatchNumber(current.data().merchantId(), current.data().number() + 1));
    }

    @Override
    public Object group(BatchNumber value) {
      return value.merchantId();
    }
  };

  SecondaryIdModel.Group<AcquirerBatchNumber> AcquirerBatch =  new SecondaryIdModel.Group<>() {
    @Override
    public boolean isInitial(AcquirerBatchNumber value) {
      return false;
    }

    @Override
    public SecondaryId<AcquirerBatchNumber> initial(Object group) {
      return new SecondaryId<>(AcquirerBatchNumber, new AcquirerBatchNumber((String)group, 1));
    }

    @Override
    public SecondaryId<AcquirerBatchNumber> next(SecondaryId<AcquirerBatchNumber> current) {
      return new SecondaryId<>(current.model(), new AcquirerBatchNumber(current.data().merchantId(), current.data().number() + 1));
    }

    @Override
    public Object group(AcquirerBatchNumber value) {
      return value.merchantId();
    }
  };
}
