package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.http.ParsedAuthorizationClaims.Invalid;
import com.github.thxmasj.statemachine.http.ParsedAuthorizationClaims.Valid;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;

public sealed interface ParsedAuthorizationClaims permits Invalid, Valid {

  record Invalid(String error) implements ParsedAuthorizationClaims {}

  record Valid(String subject) implements ParsedAuthorizationClaims {}

  static ParsedAuthorizationClaims invalid(String error) {
    return new Invalid(error);
  }

  static ParsedAuthorizationClaims valid(String value) {
    return new Valid(value);
  }

  default boolean isValid() {
    return this instanceof Valid;
  }

  default boolean isInvalid() {
    return this instanceof Invalid;
  }

  default Valid valid() {
    if (this instanceof Valid s) return s;
    throw new IllegalStateException();
  }

  default Invalid invalid() {
    if (this instanceof Invalid f) return f;
    throw new IllegalStateException();
  }

  static ParsedAuthorizationClaims claimsFromBearerToken(HttpRequestMessage message) {
    String v = message.headerValue("Authorization");
    if (v == null)
      return new Invalid("Authorization header missing");
    if (!v.startsWith("Bearer "))
      return new Invalid("Authorization token is not of type Bearer");
    String token = v.substring("Bearer ".length());
    SignedJWT jws;
    try {
      jws = SignedJWT.parse(token);
      return new Valid(jws.getJWTClaimsSet().getSubject());
    } catch (ParseException e) {
      return new Invalid("Bearer token is invalid");
    }
  }


}
