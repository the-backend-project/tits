package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.http.HttpAuthorizationExtractor.Subject.Failure;
import com.github.thxmasj.statemachine.http.HttpAuthorizationExtractor.Subject.Success;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.function.Function;

public abstract class HttpAuthorizationExtractor {

  public sealed interface Subject permits Failure, Success {

    record Failure(String error) implements Subject {}
    record Success(String value) implements Subject {}

    static Subject failure(String error) {
      return new Failure(error);
    }

    static Subject success(String value) {
      return new Success(value);
    }

  }

  public static String subjectFromBasicAuth(HttpRequestMessage message) {
    String v = message.headerValue("Authorization");
    if (v == null)
      return null;
    if (!v.startsWith("Basic "))
      return null;
    String credentials = new String(Base64.getDecoder().decode(v.substring("Basic ".length()).getBytes()));
    String[] credentialsComponents = credentials.split(":", 2);
    if (credentialsComponents.length != 2)
      return null;
    return URLDecoder.decode(credentialsComponents[0], StandardCharsets.UTF_8);
  }

  public static Subject subjectFromBearerToken(HttpRequestMessage message) {
    String v = message.headerValue("Authorization");
    if (v == null)
      return new Failure("Authorization header missing");
    if (!v.startsWith("Bearer "))
      return new Failure("Authorization token is not of type Bearer");
    String token = v.substring("Bearer ".length());
    SignedJWT jws;
    try {
      jws = SignedJWT.parse(token);
      return new Success(jws.getJWTClaimsSet().getSubject());
    } catch (ParseException e) {
      return new Failure("Bearer token is invalid");
    }
  }

  public static Function<HttpRequestMessage, String> value(String value) {
    return _ -> value;
  }

}
