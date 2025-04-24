package com.github.thxmasj.statemachine.message.http;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class Parser {

  public static Map<String, String> headers(String message) {
    return headersOfHead(head(message));
  }

  public static String body(String message) {
    int indexOfBody = indexOfBody(message);
    if (indexOfBody == -1)
      return null;
    String substring = message.substring(indexOfBody);
    return substring.isBlank() ? null : substring;
  }

  private static Map<String, String> headersOfHead(String head) {
    if (head.isBlank())
      return Map.of();
    String[] lines = head.split("\n");
    if (lines.length < 2)
      return Map.of();
    TreeMap<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 1; i < lines.length; i++) {
      int separatorIndex = lines[i].indexOf(":");
      if (separatorIndex == -1)
        throw new IllegalStateException("Invalid header -- no separator: <" + lines[i] + ">");
      map.put(lines[i].substring(0, separatorIndex).trim(), lines[i].substring(separatorIndex + 1).trim());
    }
    return Collections.unmodifiableMap(map);
  }

  private static String head(String message) {
    int indexOfBody = indexOfBody(message);
    return message.substring(0, indexOfBody > -1 ? indexOfBody : message.length());
  }

  private static int indexOfBody(String message) {
    int i = message.indexOf("\n\n");
    return (i > -1) ? i + 2 : i;
  }


}
