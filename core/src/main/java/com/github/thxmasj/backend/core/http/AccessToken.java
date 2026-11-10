package com.github.thxmasj.backend.core.http;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

public class AccessToken {

  private final String token;
  private final Instant expiry;

  public AccessToken(String token, long expiresIn, Clock clock) {
    this.token = token;
    this.expiry = clock.instant().plusSeconds(expiresIn);
  }

  static AccessToken parse(String input, Clock clock) {
    return Parser.parse(input, clock);
  }


  public String token() {
    return token;
  }

  public Instant expiry() {
    return expiry;
  }

  private static class Parser {

    private static final Pattern tokenPattern = Pattern.compile("\"access_token\"\\s*:\\s*\"(.+?)\"");
    private static final Pattern expiryPattern = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

    static AccessToken parse(String input, Clock clock) {
      var tokenMatcher = tokenPattern.matcher(input);
      var expiryMatcher = expiryPattern.matcher(input);
      if (!tokenMatcher.find()) {
        throw new IllegalArgumentException("No access_token attribute found in " + input);
      }
      if (!expiryMatcher.find()) {
        throw new IllegalArgumentException("No expires_in attribute found in " + input);
      }
      return new AccessToken(tokenMatcher.group(1), Long.parseLong(expiryMatcher.group(1)), clock);
    }
  }

}
