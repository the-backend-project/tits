package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/// An upstream OpenID Connect provider which signs in the user it is told to without asking, or fails with the error
/// it is told to. It checks the requests of the identity provider as a strict provider would: the client credentials,
/// the redirect URI and the PKCE verifier.
final class UpstreamStub implements AutoCloseable {

  static final String clientId = "identity-provider";
  static final String clientSecret = "the-secret-of-the-identity-provider-upstream";

  record User(String subject, String email, String name) {}

  private record Pending(String redirectUri, String nonce, String challenge, User user) {}

  private final RSAKey key;
  private final HttpServer server;
  private final Map<String, Pending> codes = new ConcurrentHashMap<>();
  /// The codes issued, to check that they don't enter the event log
  final List<String> issuedCodes = new CopyOnWriteArrayList<>();
  private volatile User user = new User("upstream-user-1", "ada@example.com", "Ada Lovelace");
  private volatile String error;

  UpstreamStub() throws JOSEException, IOException {
    key = new RSAKeyGenerator(2048).keyID("upstream").generate();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      Response response;
      if (method.equals("GET") && path.equals("/.well-known/openid-configuration"))
        response = Response.json(200, metadata());
      else if (method.equals("GET") && path.equals("/jwks"))
        response = Response.json(200, new JWKSet(key.toPublicJWK()).toString());
      else if (method.equals("GET") && path.equals("/authorize"))
        response = authorize(Form.parse(rawQuery(exchange.getRequestURI()).getBytes(UTF_8)));
      else if (method.equals("POST") && path.equals("/token"))
        response = token(exchange.getRequestHeaders().getFirst("Authorization"), Form.parse(exchange.getRequestBody().readAllBytes()));
      else
        response = Response.json(404, "{}");
      if (response.location() != null)
        exchange.getResponseHeaders().set("Location", response.location());
      if (response.body() != null) {
        byte[] body = response.body().getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
      } else {
        exchange.sendResponseHeaders(response.status(), -1);
      }
    }
  }

  private record Response(int status, String location, String body) {

    static Response json(int status, String body) {
      return new Response(status, null, body);
    }

    static Response redirect(String location) {
      return new Response(302, location, null);
    }

  }

  String issuer() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  /// The user signed in next
  void signInAs(User user) {
    this.user = user;
    this.error = null;
  }

  /// The error the next sign-in fails with, e.g. `access_denied` for a user who cancels
  void failWith(String error) {
    this.error = error;
  }

  private String metadata() {
    return DataType.objectMapper.writeValueAsString(Map.of(
        "issuer", issuer(),
        "authorization_endpoint", issuer() + "/authorize",
        "token_endpoint", issuer() + "/token",
        "jwks_uri", issuer() + "/jwks",
        "response_types_supported", List.of("code"),
        "subject_types_supported", List.of("public"),
        "id_token_signing_alg_values_supported", List.of("RS256"),
        "code_challenge_methods_supported", List.of("S256")
    ));
  }

  private Response authorize(Map<String, String> query) {
    if (!clientId.equals(query.get("client_id")) || !"code".equals(query.get("response_type")) || !"S256".equals(query.get("code_challenge_method")))
      return Response.json(400, "{\"error\":\"invalid_request\"}");
    String redirectUri = query.get("redirect_uri");
    String state = URLEncoder.encode(query.get("state"), UTF_8);
    if (error != null) {
      String failure = error;
      error = null;
      return Response.redirect(redirectUri + "?error=" + failure + "&state=" + state);
    }
    String code = Secrets.generate();
    codes.put(code, new Pending(redirectUri, query.get("nonce"), query.get("code_challenge"), user));
    issuedCodes.add(code);
    return Response.redirect(redirectUri + "?code=" + code + "&state=" + state);
  }

  private Response token(String authorization, Map<String, String> form) {
    String expected = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(UTF_8));
    if (!expected.equals(authorization))
      return Response.json(401, "{\"error\":\"invalid_client\"}");
    Pending pending = codes.remove(form.getOrDefault("code", ""));
    if (pending == null
        || !pending.redirectUri().equals(form.get("redirect_uri"))
        || form.get("code_verifier") == null
        || !pending.challenge().equals(Secrets.hash(form.get("code_verifier"))))
      return Response.json(400, "{\"error\":\"invalid_grant\"}");
    Instant now = Instant.now();
    var claims = new JWTClaimsSet.Builder()
        .issuer(issuer())
        .audience(clientId)
        .subject(pending.user().subject())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .claim("nonce", pending.nonce())
        .claim("auth_time", now.getEpochSecond())
        .claim("email", pending.user().email())
        .claim("email_verified", true)
        .claim("name", pending.user().name())
        .build();
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return Response.json(200, DataType.objectMapper.writeValueAsString(Map.of(
        "access_token", "an-upstream-access-token",
        "token_type", "Bearer",
        "expires_in", 300,
        "id_token", jwt.serialize()
    )));
  }

  private static String rawQuery(URI uri) {
    return uri.getRawQuery() != null ? uri.getRawQuery() : "";
  }

  @Override
  public void close() {
    server.stop(0);
  }

}
