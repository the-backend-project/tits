package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public interface Identifiers {

  SecondaryIdModel<String> MerchantId = new SecondaryIdModel<>(){
    @Override
    public String name() {
      return "MerchantId";
    }

    @Override
    public List<SchemaNames.Column> columns() {
      return List.of(
          new SchemaNames.Column("MerchantId", "VARCHAR(36)", e -> e)
      );
    }

    @Override
    public SecondaryId<String> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(
            this,
            resultSet.getString("MerchantId")
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };

  SecondaryIdModel<BatchNumber> BatchNumber =  new SecondaryIdModel<>() {
    @Override
    public String name() {
      return "BatchNumber";
    }

    @Override
    public List<SchemaNames.Column> columns() {
      return List.of(
          new SchemaNames.Column("MerchantId", "VARCHAR(36)", e -> ((BatchNumber) e).merchantId()),
          new SchemaNames.Column("BatchNumber", "BIGINT", e -> ((BatchNumber) e).number())
      );
    }

    @Override
    public Group<BatchNumber> group() {
      return IdentifierGroups.Batch;
    }

    @Override
    public SecondaryId<BatchNumber> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(
            this,
            new BatchNumber(resultSet.getString("MerchantId"), resultSet.getLong("BatchNumber"))
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

  };

  SecondaryIdModel<AcquirerBatchNumber> AcquirerBatchNumber =   new SecondaryIdModel<>() {
    @Override
    public String name() {
      return "AcquirerBatchNumber";
    }

    @Override
    public List<SchemaNames.Column> columns() {
      return List.of(
          new SchemaNames.Column("MerchantId", "VARCHAR(36)", e -> ((AcquirerBatchNumber) e).merchantId()),
          new SchemaNames.Column("BatchNumber", "BIGINT", e -> ((AcquirerBatchNumber) e).number())
      );
    }

    @Override
    public Group<AcquirerBatchNumber> group() {
      return IdentifierGroups.AcquirerBatch;
    }

    @Override
    public SecondaryId<AcquirerBatchNumber> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(
            this,
            new AcquirerBatchNumber(resultSet.getString("MerchantId"), resultSet.getInt("BatchNumber"))
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };
}
