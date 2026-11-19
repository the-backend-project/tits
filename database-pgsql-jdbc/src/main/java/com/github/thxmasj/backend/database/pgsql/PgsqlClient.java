package com.github.thxmasj.backend.database.pgsql;

import com.github.thxmasj.backend.core.internal.database.jdbc.JDBCClient;
import com.github.thxmasj.backend.database.pgsql.internal.ConstraintViolations;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/// Maps PostgreSQL's unique violations to primary key and unique index violations by the name of the constraint
class PgsqlClient extends JDBCClient {

  PgsqlClient(DataSource dataSource) {
    super(dataSource);
  }

  @Override
  protected Throwable constraintViolation(SQLException error, String queryName, String errorMessage) {
    if (!(error instanceof PSQLException e && e.getServerErrorMessage() != null))
      return null;
    ServerErrorMessage serverError = e.getServerErrorMessage();
    return ConstraintViolations.constraintViolation(e.getSQLState(), serverError.getDetail(), serverError.getTable(), serverError.getConstraint(), queryName, errorMessage, e);
  }

}
