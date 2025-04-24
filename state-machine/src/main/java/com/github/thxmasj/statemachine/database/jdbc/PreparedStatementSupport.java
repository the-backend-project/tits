package com.github.thxmasj.statemachine.database.jdbc;

import java.sql.Connection;
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

  public static PreparedStatement prepare(String sql, Map<String, Object> parameters, Connection connection) throws SQLException {
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
        statement.setObject(position + 1, parameters.get(entry.getKey()));
      }
    }
    return statement;
  }


}
