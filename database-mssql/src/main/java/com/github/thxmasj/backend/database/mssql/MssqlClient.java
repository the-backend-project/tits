package com.github.thxmasj.backend.database.mssql;

import com.github.thxmasj.backend.core.database.DatabaseClient.DataIntegrityViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.PrimaryKeyConstraintViolation;
import com.github.thxmasj.backend.core.database.DatabaseClient.UniqueIndexConstraintViolation;
import com.github.thxmasj.backend.core.database.Parameter;
import com.github.thxmasj.backend.core.internal.database.jdbc.JDBCClient;
import com.microsoft.sqlserver.jdbc.SQLServerException;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/// Stores UUIDs as BINARY(16), and maps SQL Server's primary key and unique index violations, whose details are only
/// given in their messages
///
/// A UUID is stored as its 16 bytes in the order of its string form. SQL Server compares BINARY values byte by byte
/// from the first, so time-ordered UUIDs (version 7) are ordered by time in its indexes. It compares UNIQUEIDENTIFIER
/// values from their last 6 bytes, where a version 7 UUID has random bits, so would insert them at random places.
class MssqlClient extends JDBCClient {

  private static final Pattern binaryUuidPattern = Pattern.compile("^0x(\\p{XDigit}{32})(?=,|$)");

  private static final Pattern duplicateKeyRowPattern = Pattern.compile(
      "Cannot insert duplicate key row in object '(.*)\\.(.*)' with unique index '(.*)'. The duplicate key value is \\((.*)\\).");
  private static final Pattern primaryKeyViolationPattern = Pattern.compile(
      "Violation of PRIMARY KEY constraint '(.*)'. Cannot insert duplicate key in object '(.*)\\.(.*)'. The duplicate key value is \\((.*)\\).");

  MssqlClient(MssqlDatabase database, DataSource dataSource) {
    super(database, dataSource);
  }

  @Override
  protected void setParameter(PreparedStatement statement, int index, Parameter<?> parameter) throws SQLException {
    if (parameter.value() instanceof UUID uuid)
      statement.setBytes(index, bytes(uuid));
    else if (parameter.value() == null && parameter.type() == UUID.class)
      statement.setNull(index, Types.BINARY);
    else
      super.setParameter(statement, index, parameter);
  }

  @Override
  protected <T> T getColumn(ResultSet resultSet, String name, Class<T> type) throws SQLException {
    if (type != UUID.class)
      return super.getColumn(resultSet, name, type);
    byte[] bytes = resultSet.getBytes(name);
    return bytes == null ? null : type.cast(uuid(bytes));
  }

  static byte[] bytes(UUID uuid) {
    return ByteBuffer.allocate(16)
        .putLong(uuid.getMostSignificantBits())
        .putLong(uuid.getLeastSignificantBits())
        .array();
  }

  static UUID uuid(byte[] bytes) {
    if (bytes.length != 16)
      throw new IllegalArgumentException("A UUID has 16 bytes, not " + bytes.length);
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  /// The duplicate key with its leading binary as a UUID, e.g. "0x019A...C3, 1" as "019a...c3, 1". The keys of the
  /// primary keys and the unique index of the schema lead with a UUID column, which SQL Server reports as a binary.
  static String withUuid(String duplicateKey) {
    Matcher matcher = binaryUuidPattern.matcher(duplicateKey);
    if (!matcher.find())
      return duplicateKey;
    return uuid(HexFormat.of().parseHex(matcher.group(1))) + duplicateKey.substring(matcher.end());
  }

  @Override
  protected Throwable constraintViolation(SQLException error, String queryName, String errorMessage) {
    return switch (error) {
      case SQLServerException e when e.getSQLServerError().getErrorNumber() == 2627 -> {
        Matcher primaryKeyViolationMatcher = primaryKeyViolationPattern.matcher(e.getMessage());
        if (primaryKeyViolationMatcher.matches())
          yield new PrimaryKeyConstraintViolation(queryName, null, withUuid(primaryKeyViolationMatcher.group(4)), primaryKeyViolationMatcher.group(3), primaryKeyViolationMatcher.group(1), e);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage, e);
      }
      case SQLServerException f when f.getSQLServerError().getErrorNumber() == 2601 -> {
        Matcher duplicateKeyRowMatcher = duplicateKeyRowPattern.matcher(f.getMessage());
        if (duplicateKeyRowMatcher.matches())
          yield new UniqueIndexConstraintViolation(queryName, null, withUuid(duplicateKeyRowMatcher.group(4)), duplicateKeyRowMatcher.group(2), duplicateKeyRowMatcher.group(3), f);
        else
          yield new DataIntegrityViolation(queryName, null, "N/A", errorMessage, f);
      }
      default -> null;
    };
  }

}
