package com.github.thxmasj.backend.http.inbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/// The parameters of an `application/x-www-form-urlencoded` body or the query of a URI, e.g. of the requests and
/// redirects of OpenID Connect
public final class Form {

  private Form() {}

  /// @throws IllegalArgumentException if the body is malformed or has a parameter more than once, which RFC 6749
  ///                                  section 3.2 forbids
  public static Map<String, String> parse(byte[] body) {
    var parameters = new LinkedHashMap<String, String>();
    if (body == null || body.length == 0)
      return parameters;
    for (String pair : new String(body, UTF_8).split("&")) {
      if (pair.isEmpty())
        continue;
      int i = pair.indexOf('=');
      String name = decode(i < 0 ? pair : pair.substring(0, i));
      String value = i < 0 ? "" : decode(pair.substring(i + 1));
      if (parameters.put(name, value) != null)
        throw new IllegalArgumentException("Parameter " + name + " is repeated");
    }
    return parameters;
  }

  /// The parameters of the raw query of a URI, as [#parse(byte[])], or none for a URI without a query
  ///
  /// @throws IllegalArgumentException if the query is malformed or has a parameter more than once
  public static Map<String, String> parse(String rawQuery) {
    return parse(rawQuery != null ? rawQuery.getBytes(UTF_8) : null);
  }

  /// @throws IllegalArgumentException if the value has a malformed escape
  public static String decode(String value) {
    // Throws IllegalArgumentException on malformed escapes
    return URLDecoder.decode(value, UTF_8);
  }

  /// The parameters encoded as a form, or the query of a URI
  public static String encode(Map<String, String> parameters) {
    return parameters.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
  }

}
