package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public enum Identifiers implements SchemaNames.SecondaryIdModel {
  BatchNumber {
    @Override
    public List<SchemaNames.Column> columns() {
      return List.of(
          new SchemaNames.Column("MerchantId", "VARCHAR(36)", e -> ((BatchNumber) e).merchantId()),
          new SchemaNames.Column("BatchNumber", "BIGINT", e -> ((BatchNumber) e).number())
      );
    }

    @Override
    public Group group() {
      return IdentifierGroups.Batch;
    }

    @Override
    public SecondaryId map(ResultSet resultSet) {
      try {
        return new SecondaryId(
            this,
            new BatchNumber(resultSet.getString("MerchantId"), resultSet.getLong("BatchNumber"))
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

  },
  AcquirerBatchNumber {
    @Override
    public List<SchemaNames.Column> columns() {
      return List.of(
          new SchemaNames.Column("MerchantId", "VARCHAR(36)", e -> ((AcquirerBatchNumber) e).merchantId()),
          new SchemaNames.Column("BatchNumber", "BIGINT", e -> ((AcquirerBatchNumber) e).number())
      );
    }

    @Override
    public Group group() {
      return IdentifierGroups.AcquirerBatch;
    }

    @Override
    public SecondaryId map(ResultSet resultSet) {
      try {
        return new SecondaryId(
            this,
            new AcquirerBatchNumber(resultSet.getString("MerchantId"), resultSet.getInt("BatchNumber"))
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
