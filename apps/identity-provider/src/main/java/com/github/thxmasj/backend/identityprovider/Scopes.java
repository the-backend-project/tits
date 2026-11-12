package com.github.thxmasj.backend.identityprovider;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/// Scopes as defined by RFC 6749 section 3.3: a list of space-delimited, case-sensitive strings
final class Scopes {

  private static final Pattern scopeToken = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

  private Scopes() {}

  /// Whether the value is a list of scopes, i.e. scope tokens separated by single spaces
  static boolean isValid(String value) {
    return value != null && !value.isEmpty() && Arrays.stream(value.split(" ", -1)).allMatch(s -> scopeToken.matcher(s).matches());
  }

  /// The distinct scopes of a valid value, in order
  static List<String> parse(String value) {
    return value.isEmpty() ? List.of() : Arrays.stream(value.split(" ")).distinct().toList();
  }

  static String format(List<String> scopes) {
    return String.join(" ", scopes);
  }

}
