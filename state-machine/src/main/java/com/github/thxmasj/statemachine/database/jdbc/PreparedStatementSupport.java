package com.github.thxmasj.statemachine.database.jdbc;

import com.github.thxmasj.statemachine.database.Parameter;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PreparedStatementSupport {

  private static final Pattern parameterPattern = Pattern.compile(":[a-zA-Z0-9_]+");

  public static PreparedStatement prepare(String sql, Map<String, Parameter<?>> parameters, Connection connection) throws SQLException {
    Matcher matcher = parameterPattern.matcher(sql);
    StringBuilder builder = new StringBuilder();
    Map<String, List<Integer>> positions = new HashMap<>();
    int counter = 0;
    while (matcher.find()) {
      int position = counter++;
      positions.computeIfAbsent(matcher.group().substring(1), _ -> new ArrayList<>()).add(position);
      matcher.appendReplacement(builder, "?");
    }
    String preparedSql = matcher.appendTail(builder).toString();
    //noinspection SqlSourceToSinkFlow
    PreparedStatement statement = connection.prepareStatement(preparedSql);
    for (Map.Entry<String, List<Integer>> entry : positions.entrySet()) {
      for (Integer position : entry.getValue()) {
        Parameter<?> parameter = parameters.get(entry.getKey());
        if (parameter == null) throw new IllegalStateException("Parameter " + entry.getKey() + " not found in " + parameters.keySet());
        if (parameter.value() == null)
          statement.setNull(position + 1, jdbcType(parameter.type()).getVendorTypeNumber());
        else
          statement.setObject(position + 1, parameter.value());
      }
    }
    return statement;
  }

  public static JDBCType jdbcType(Class<?> clazz) {
    if (clazz == null) {
      return JDBCType.NULL;
    }
    return TYPE_MAP.getOrDefault(clazz, JDBCType.OTHER);
  }

  private static final Map<Class<?>, JDBCType> TYPE_MAP = new HashMap<>();

  static {
    // Strings & Text
    TYPE_MAP.put(String.class, JDBCType.VARCHAR);
    TYPE_MAP.put(Character.class, JDBCType.CHAR);
    TYPE_MAP.put(char.class, JDBCType.CHAR);

    // Numerics
    TYPE_MAP.put(Byte.class, JDBCType.TINYINT);
    TYPE_MAP.put(byte.class, JDBCType.TINYINT);
    TYPE_MAP.put(Short.class, JDBCType.SMALLINT);
    TYPE_MAP.put(short.class, JDBCType.SMALLINT);
    TYPE_MAP.put(Integer.class, JDBCType.INTEGER);
    TYPE_MAP.put(int.class, JDBCType.INTEGER);
    TYPE_MAP.put(Long.class, JDBCType.BIGINT);
    TYPE_MAP.put(long.class, JDBCType.BIGINT);
    TYPE_MAP.put(Float.class, JDBCType.REAL);
    TYPE_MAP.put(float.class, JDBCType.REAL);
    TYPE_MAP.put(Double.class, JDBCType.DOUBLE);
    TYPE_MAP.put(double.class, JDBCType.DOUBLE);
    TYPE_MAP.put(java.math.BigDecimal.class, JDBCType.DECIMAL);

    // Booleans
    TYPE_MAP.put(Boolean.class, JDBCType.BOOLEAN);
    TYPE_MAP.put(boolean.class, JDBCType.BOOLEAN);

    // Binary Data
    TYPE_MAP.put(byte[].class, JDBCType.VARBINARY);
    TYPE_MAP.put(Byte[].class, JDBCType.VARBINARY);

    // Date & Time
    TYPE_MAP.put(java.time.LocalDate.class, JDBCType.DATE);
    TYPE_MAP.put(java.time.LocalTime.class, JDBCType.TIME);
    TYPE_MAP.put(java.time.LocalDateTime.class, JDBCType.TIMESTAMP);
    TYPE_MAP.put(java.time.OffsetDateTime.class, JDBCType.TIMESTAMP_WITH_TIMEZONE);
    TYPE_MAP.put(java.time.OffsetTime.class, JDBCType.TIME_WITH_TIMEZONE);
  }

}
