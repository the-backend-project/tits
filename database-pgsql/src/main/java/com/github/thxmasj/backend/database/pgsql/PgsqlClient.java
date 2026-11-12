package com.github.thxmasj.backend.database.pgsql;

import com.github.thxmasj.backend.core.database.Client.DataIntegrityViolation;
import com.github.thxmasj.backend.core.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.Client.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/// Maps PostgreSQL's unique violations to primary key and unique index violations by the name of the constraint
public class PgsqlClient extends JDBCClient {

  private static final String UNIQUE_VIOLATION = "23505";
  private static final Pattern duplicateKeyPattern = Pattern.compile("Key \\(.*\\)=\\((.*)\\) already exists\\.");

  public PgsqlClient(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected Throwable constraintViolation(SQLException error, String queryName, String errorMessage) {
    if (!(error instanceof PSQLException e && e.getServerErrorMessage() != null))
      return null;
    ServerErrorMessage serverError = e.getServerErrorMessage();
    return constraintViolation(e.getSQLState(), serverError.getDetail(), serverError.getTable(), serverError.getConstraint(), queryName, errorMessage, e);
  }

  /// The constraint violation of an error reported by PostgreSQL, or null if it is not a unique violation. It is shared
  /// with the client of PostgreSQL's R2DBC driver.
  ///
  /// @param errorMessage the error's message with the query and its parameters
  public static Throwable constraintViolation(
      String sqlState,
      String detail,
      String table,
      String constraint,
      String queryName,
      String errorMessage,
      Throwable error
  ) {
    if (!UNIQUE_VIOLATION.equals(sqlState))
      return null;
    Matcher duplicateKeyMatcher = duplicateKeyPattern.matcher(String.valueOf(detail));
    if (table == null || constraint == null || !duplicateKeyMatcher.matches())
      return new DataIntegrityViolation(queryName, null, "N/A", errorMessage, error);
    // Primary keys are named with a "pk" prefix, see CreateSchema
    else if (constraint.startsWith("pk"))
      return new PrimaryKeyConstraintViolation(queryName, null, duplicateKeyMatcher.group(1), table, constraint, error);
    else
      return new UniqueIndexConstraintViolation(queryName, null, duplicateKeyMatcher.group(1), table, constraint, error);
  }

}
