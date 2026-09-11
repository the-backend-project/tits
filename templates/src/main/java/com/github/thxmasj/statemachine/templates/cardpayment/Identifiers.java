package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public interface Identifiers {

  SecondaryIdModel<String> MerchantId = new SecondaryIdModel<>(){
    @Override
    public UUID id() {
      return UUID.fromString("2c1166f0-ad63-4387-ae88-c710eee9c9eb");
    }

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
    public UUID id() {
      return UUID.fromString("e0ca999b-0853-43eb-b1a6-f0f2ef6130b5");
    }

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

  SecondaryIdModel<AcquirerBatchNumber> AcquirerBatchNumber = new SecondaryIdModel<>() {
    @Override
    public UUID id() {
      return UUID.fromString("8708bc15-006c-4476-8811-5d5e2511bcd3");
    }

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
