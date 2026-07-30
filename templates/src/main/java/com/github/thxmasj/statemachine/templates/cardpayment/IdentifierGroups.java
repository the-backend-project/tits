package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.ColumnOrder;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;

import java.util.List;

public interface IdentifierGroups {
  SecondaryIdModel.Group<BatchNumber> Batch = new SecondaryIdModel.Group<>() {
    @Override
    public List<Column> groupColumns() {
      return List.of(
          new Column("MerchantId", "VARCHAR(36)", e -> e)
      );
    }

    @Override
    public List<ColumnOrder> groupOrdering() {
      return List.of(
          new ColumnOrder(new Column("BatchNumber", null, e -> ((BatchNumber)e).number()), true)
      );
    }

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
    public List<Column> groupColumns() {
      return List.of(
          new Column("MerchantId", "VARCHAR(36)", e -> e)
      );
    }

    @Override
    public List<ColumnOrder> groupOrdering() {
      return List.of(
          new ColumnOrder(new Column("BatchNumber", null, e -> ((AcquirerBatchNumber)e).number()), true)
      );
    }

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
