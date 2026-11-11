package com.github.thxmasj.backend.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

public enum Dialect {

  SQL_SERVER,
  POSTGRES;

  /**
   * The dialect of the database behind the data source, as reported by the driver.
   */
  public static Dialect of(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      String productName = connection.getMetaData().getDatabaseProductName();
      return switch (productName) {
        case "Microsoft SQL Server" -> SQL_SERVER;
        case "PostgreSQL" -> POSTGRES;
        default -> throw new IllegalArgumentException("Unsupported database " + productName);
      };
    } catch (SQLException e) {
      throw new RuntimeException("Failed to detect database dialect: " + e.getMessage(), e);
    }
  }

}
