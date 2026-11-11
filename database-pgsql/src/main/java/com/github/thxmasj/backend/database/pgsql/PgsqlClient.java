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
    if (!(error instanceof PSQLException e && UNIQUE_VIOLATION.equals(e.getSQLState()) && e.getServerErrorMessage() != null))
      return null;
    ServerErrorMessage serverError = e.getServerErrorMessage();
    Matcher duplicateKeyMatcher = duplicateKeyPattern.matcher(String.valueOf(serverError.getDetail()));
    if (serverError.getTable() == null || serverError.getConstraint() == null || !duplicateKeyMatcher.matches())
      return new DataIntegrityViolation(queryName, null, "N/A", errorMessage, e);
    // Primary keys are named with a "pk" prefix, see CreateSchema
    else if (serverError.getConstraint().startsWith("pk"))
      return new PrimaryKeyConstraintViolation(queryName, null, duplicateKeyMatcher.group(1), serverError.getTable(), serverError.getConstraint(), e);
    else
      return new UniqueIndexConstraintViolation(queryName, null, duplicateKeyMatcher.group(1), serverError.getTable(), serverError.getConstraint(), e);
  }

}
