package com.github.thxmasj.backend.backoffice;

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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/// An OpenID Connect provider which signs in the user it is told to without asking, or fails with the error it is told
/// to. It checks the requests of the backoffice as a strict provider would: the client credentials, the redirect URI
/// and the PKCE verifier.
final class ProviderStub implements AutoCloseable {

  static final String clientId = "backoffice";
  static final String clientSecret = "the-secret-of-the-backoffice";

  /// @param roles the roles of the user, in the claim `roles`, or null for none
  record User(String subject, String email, String name, List<String> roles) {}

  private record Pending(String redirectUri, String nonce, String challenge, User user) {}

  private final RSAKey key;
  private final HttpServer server;
  private final Map<String, Pending> codes = new ConcurrentHashMap<>();
  /// The codes and ID tokens issued, to check that they don't enter the event log
  final List<String> issued = new CopyOnWriteArrayList<>();
  private volatile User user;
  private volatile String error;

  ProviderStub() throws JOSEException, IOException {
    key = new RSAKeyGenerator(2048).keyID("provider").generate();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.start();
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

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();
      int status;
      String location = null;
      String body = null;
      if (method.equals("GET") && path.equals("/.well-known/openid-configuration")) {
        status = 200;
        body = metadata();
      } else if (method.equals("GET") && path.equals("/jwks")) {
        status = 200;
        body = new JWKSet(key.toPublicJWK()).toString();
      } else if (method.equals("GET") && path.equals("/authorize")) {
        location = authorize(OpenIdConnect.parse(exchange.getRequestURI().getRawQuery()));
        status = location != null ? 302 : 400;
      } else if (method.equals("POST") && path.equals("/token")) {
        String[] response = token(exchange.getRequestHeaders().getFirst("Authorization"), OpenIdConnect.parse(new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
        status = Integer.parseInt(response[0]);
        body = response[1];
      } else {
        status = 404;
      }
      if (location != null)
        exchange.getResponseHeaders().set("Location", location);
      if (body != null) {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } else {
        exchange.sendResponseHeaders(status, -1);
      }
    }
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

  /// The redirect back to the client, or null if the request is invalid
  private String authorize(Map<String, String> query) {
    if (!clientId.equals(query.get("client_id")) || !"code".equals(query.get("response_type")) || !"S256".equals(query.get("code_challenge_method")))
      return null;
    String redirectUri = query.get("redirect_uri");
    String state = URLEncoder.encode(query.get("state"), UTF_8);
    if (error != null) {
      String failure = error;
      error = null;
      return redirectUri + "?error=" + failure + "&state=" + state;
    }
    String code = OpenIdConnect.generateSecret();
    codes.put(code, new Pending(redirectUri, query.get("nonce"), query.get("code_challenge"), user));
    issued.add(code);
    return redirectUri + "?code=" + code + "&state=" + state;
  }

  private String[] token(String authorization, Map<String, String> form) {
    String expected = "Basic " + Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(UTF_8));
    if (!expected.equals(authorization))
      return new String[] {"401", "{\"error\":\"invalid_client\"}"};
    Pending pending = codes.remove(form.getOrDefault("code", ""));
    if (pending == null
        || !pending.redirectUri().equals(form.get("redirect_uri"))
        || form.get("code_verifier") == null
        || !pending.challenge().equals(OpenIdConnect.hash(form.get("code_verifier"))))
      return new String[] {"400", "{\"error\":\"invalid_grant\"}"};
    Instant now = Instant.now();
    var claims = new JWTClaimsSet.Builder()
        .issuer(issuer())
        .audience(clientId)
        .subject(pending.user().subject())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .claim("nonce", pending.nonce())
        .claim("email", pending.user().email())
        .claim("name", pending.user().name());
    if (pending.user().roles() != null)
      claims.claim("roles", pending.user().roles());
    var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    issued.add(jwt.serialize());
    return new String[] {"200", DataType.objectMapper.writeValueAsString(Map.of(
        "access_token", "an-access-token-of-the-provider",
        "token_type", "Bearer",
        "expires_in", 300,
        "id_token", jwt.serialize()
    ))};
  }

  @Override
  public void close() {
    server.stop(0);
  }

}
