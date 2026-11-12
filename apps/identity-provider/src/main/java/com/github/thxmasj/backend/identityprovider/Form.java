package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;

/// The parameters of an `application/x-www-form-urlencoded` body
final class Form {

  private Form() {}

  /// @throws IllegalArgumentException if the body is malformed or has a parameter more than once, which RFC 6749
  ///                                  section 3.2 forbids
  static Map<String, String> parse(byte[] body) {
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

  static String decode(String value) {
    // Throws IllegalArgumentException on malformed escapes
    return URLDecoder.decode(value, UTF_8);
  }

}
