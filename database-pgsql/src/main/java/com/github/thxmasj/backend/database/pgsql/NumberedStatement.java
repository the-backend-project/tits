package com.github.thxmasj.backend.database.pgsql;

import com.github.thxmasj.backend.core.database.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A statement with its named parameters, such as ":entityId", replaced by PostgreSQL's numbered ones, such as "$1".
/// It is for the clients of PostgreSQL's reactive drivers, which run one statement with parameters at a time, unlike
/// the JDBC driver.
///
/// @param parameterNames the names of the parameters, in the order of their numbers
public record NumberedStatement(String sql, List<String> parameterNames) {

  private static final Pattern parameterPattern = Pattern.compile(":[a-zA-Z0-9_]+");
  private static final Pattern statementEnd = Pattern.compile(";[ \\t]*(?:\\n|$)");

  /// The statements of the SQL, which are separated by a semicolon at the end of a line
  public static List<NumberedStatement> of(String sql) {
    return Arrays.stream(statementEnd.split(sql))
        .filter(statement -> !statement.isBlank())
        .map(NumberedStatement::numbered)
        .toList();
  }

  private static NumberedStatement numbered(String sql) {
    List<String> names = new ArrayList<>();
    String numbered = parameterPattern.matcher(sql).replaceAll(match -> {
      String name = match.group().substring(1);
      if (!names.contains(name))
        names.add(name);
      return Matcher.quoteReplacement("$" + (names.indexOf(name) + 1));
    });
    return new NumberedStatement(numbered, List.copyOf(names));
  }

  /// The parameters, in the order of their numbers
  public List<Parameter<?>> parameters(Map<String, Parameter<?>> parameters) {
    return parameterNames.stream().<Parameter<?>>map(name -> {
      Parameter<?> parameter = parameters.get(name);
      if (parameter == null)
        throw new IllegalStateException("Parameter " + name + " not found in " + parameters.keySet());
      return parameter;
    }).toList();
  }

}
