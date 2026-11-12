package com.github.thxmasj.backend.database.mssql;

import com.github.thxmasj.backend.core.database.DatabaseClient.DataIntegrityViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/// Maps SQL Server's primary key and unique index violations, whose details are only given in their messages
public class MssqlClient extends JDBCClient {

  private static final Pattern duplicateKeyRowPattern = Pattern.compile(
      "Cannot insert duplicate key row in object '(.*)\\.(.*)' with unique index '(.*)'. The duplicate key value is \\((.*)\\).");
  private static final Pattern primaryKeyViolationPattern = Pattern.compile(
      "Violation of PRIMARY KEY constraint '(.*)'. Cannot insert duplicate key in object '(.*)\\.(.*)'. The duplicate key value is \\((.*)\\).");

  public MssqlClient(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected Throwable constraintViolation(SQLException error, String queryName, String errorMessage) {
    return switch (error) {
      case SQLServerException e when e.getSQLServerError().getErrorNumber() == 2627 -> {
        Matcher primaryKeyViolationMatcher = primaryKeyViolationPattern.matcher(e.getMessage());
        if (primaryKeyViolationMatcher.matches())
          yield new PrimaryKeyConstraintViolation(queryName, null, primaryKeyViolationMatcher.group(4), primaryKeyViolationMatcher.group(3), primaryKeyViolationMatcher.group(1), e);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage, e);
      }
      case SQLServerException f when f.getSQLServerError().getErrorNumber() == 2601 -> {
        Matcher duplicateKeyRowMatcher = duplicateKeyRowPattern.matcher(f.getMessage());
        if (duplicateKeyRowMatcher.matches())
          yield new UniqueIndexConstraintViolation(queryName, null, duplicateKeyRowMatcher.group(4), duplicateKeyRowMatcher.group(2), duplicateKeyRowMatcher.group(3), f);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage, f);
      }
      default -> null;
    };
  }

}
