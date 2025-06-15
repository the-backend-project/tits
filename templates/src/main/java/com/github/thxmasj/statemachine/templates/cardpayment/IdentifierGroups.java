package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.AcquirerBatchNumber;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.BatchNumber;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.ColumnOrder;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.util.List;

public enum IdentifierGroups implements SecondaryIdModel.Group {
  Batch {
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
    public boolean isInitial(Object value) {
      return value instanceof BatchNumber sid && sid.number() == 1;
    }

    @Override
    public SecondaryId initial(Object group) {
      return new SecondaryId(BatchNumber, new BatchNumber((String)group, 1L));
    }

    @Override
    public SecondaryId next(SecondaryId current) {
      var currentIdValue = (BatchNumber)current.data();
      return new SecondaryId(BatchNumber, new BatchNumber(currentIdValue.merchantId(), currentIdValue.number() + 1));
    }

    @Override
    public Object group(Object value) {
      return value instanceof BatchNumber sid ? sid.merchantId() : null;
    }
  },
  AcquirerBatch {
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
    public boolean isInitial(Object value) {
      return false;
    }

    @Override
    public SecondaryId initial(Object group) {
      return new SecondaryId(AcquirerBatchNumber, new AcquirerBatchNumber((String)group, 1));
    }

    @Override
    public SecondaryId next(SecondaryId current) {
      var currentIdValue = (AcquirerBatchNumber)current.data();
      return new SecondaryId(current.model(), new AcquirerBatchNumber(currentIdValue.merchantId(), currentIdValue.number() + 1));
    }

    @Override
    public Object group(Object value) {
      return value instanceof AcquirerBatchNumber v ? v.merchantId() : null;
    }
  }
}
