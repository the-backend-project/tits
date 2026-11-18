package com.github.thxmasj.backend.database.pgsql.internal;

import com.github.thxmasj.backend.core.database.DatabaseClient.DataIntegrityViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Maps PostgreSQL's unique violations to primary key and unique index violations by the name of the constraint, for
/// the clients of all of PostgreSQL's drivers
public final class ConstraintViolations {

  private static final String UNIQUE_VIOLATION = "23505";
  private static final Pattern duplicateKeyPattern = Pattern.compile("Key \\(.*\\)=\\((.*)\\) already exists\\.");

  private ConstraintViolations() {}

  /// The constraint violation of an error reported by PostgreSQL, or null if it is not a unique violation.
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
