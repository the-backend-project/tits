package com.github.thxmasj.statemachine;

import static java.util.stream.Collectors.joining;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class SqlTemplate {

  private static final Pattern placeholderPattern = Pattern.compile("#(\\w+?)#"); // non-greedy

  private final String template;

  public SqlTemplate(String template) {
    this.template = template;
  }

  public String apply(Function<String, String> mapper) {
    return placeholderPattern.matcher(template).replaceAll(mr -> {
          String replacement = mapper.apply(mr.group(1));
          if (replacement == null)
            throw new IllegalStateException("Unhandled placeholder in SQL: " + mr.group(1));
          return replacement;
        }
    );
  }

  public static String repeat(int n, String s) {
    return repeatWhen(n, "", i -> true, null, s);
  }

  public static String repeat(int n, String delimiter, String s) {
    return repeatWhen(n, delimiter, i -> true, null, s);
  }

  public static String repeatWhen(int n, IntPredicate predicate, String s) {
    return repeatWhen(n, "", predicate, null, s);
  }

  public static String repeatWhen(
      int n,
      String delimiter,
      IntPredicate predicate,
      IntFunction<String> mapper,
      String s
  ) {
    return IntStream.range(0, n)
        .filter(predicate)
        .mapToObj(i -> {
          String s1 = s.replaceAll("\\?i", String.valueOf(i));
          return mapper == null ? s1 : s1.replaceAll("\\?v", mapper.apply(i));
        })
        .collect(joining(delimiter));
  }

  public static String includeIf(boolean condition, String s) {
    return condition ? s : "";
  }

  public static String includeIfOrElse(boolean condition, String s, String fallback) {
    return condition ? s : fallback;
  }

}
