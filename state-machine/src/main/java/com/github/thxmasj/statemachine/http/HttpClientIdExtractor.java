package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.nimbusds.jwt.SignedJWT;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Base64;
import java.util.function.Function;

public abstract class HttpClientIdExtractor {

  public static String fromBasicAuth(HttpRequestMessage message) {
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

  public static String fromBearerToken(HttpRequestMessage message) {
    String v = message.headerValue("Authorization");
    if (v == null)
      return null;
    if (!v.startsWith("Bearer "))
      return null;
    String token = v.substring("Bearer ".length());
    SignedJWT jws;
    try {
      jws = SignedJWT.parse(token);
      return jws.getJWTClaimsSet().getSubject();
    } catch (ParseException e) {
      return null;
    }
  }

  public static Function<HttpRequestMessage, String> value(String value) {
    return _ -> value;
  }

}
