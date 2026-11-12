package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.http.AccessTokenSupplier;
import com.github.thxmasj.backend.core.http.NettyHttpClient;
import com.github.thxmasj.backend.core.http.NettyHttpClientBuilder;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.identityprovider.Config.BootstrapClient;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;
import tools.jackson.databind.JsonNode;

/// Runs the identity provider against the test database, with a bootstrapped client registering the other clients.
@Tag("database")
public class IdentityProviderTest {

  private static final URI issuer = URI.create("https://idp.example.com");
  private static final String adminId = "admin";
  private static final String adminSecret = "the-secret-of-the-bootstrap-client";
  private static final Duration tokenLifetime = Duration.ofMinutes(5);

  private static final String schema = UUID.randomUUID().toString();
  private static StateMachine stateMachine;
  private static DisposableServer server;
  private static String adminToken;

  @BeforeAll
  public static void setUp() throws Exception {
    stateMachine = Init.stateMachine(schema, Inbox.transitions(tokenLifetime));
    IdentityProvider.bootstrap(stateMachine, new BootstrapClient(adminId, adminSecret));
    server = IdentityProvider.serve(stateMachine, new Keys(Keys.generate(), issuer, null, Clock.systemUTC()), 0);
    adminToken = json(token(adminId, adminSecret, "grant_type=client_credentials")).get("access_token").asString();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    server.disposeNow();
    stateMachine.close();
  }

  @Test
  public void whenBootstrappedAgainThenExistingClientIsKept() throws Exception {
    assertThatNoException().isThrownBy(() -> IdentityProvider.bootstrap(stateMachine, new BootstrapClient(adminId, "another-secret-of-the-bootstrap-client")));
    assertThat(token(adminId, adminSecret, "grant_type=client_credentials").statusCode()).isEqualTo(200);
  }

  @Test
  public void whenTokenRequestedThenSignedAccessTokenWithAllScopesOfClient() throws Exception {
    HttpResponse<String> response = token(adminId, adminSecret, "grant_type=client_credentials");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    JsonNode body = json(response);
    assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
    assertThat(body.get("expires_in").asLong()).isEqualTo(tokenLifetime.toSeconds());
    assertThat(body.get("scope").asString()).isEqualTo(Clients.RegistrationScope);
    SignedJWT jwt = SignedJWT.parse(body.get("access_token").asString());
    assertThat(jwt.getHeader().getType()).isEqualTo(new JOSEObjectType("at+jwt"));
    RSAKey publicKey = (RSAKey) JWKSet.parse(get("/.well-known/jwks.json").body()).getKeyByKeyId(jwt.getHeader().getKeyID());
    assertThat(publicKey.isPrivate()).isFalse();
    assertThat(jwt.verify(new RSASSAVerifier(publicKey))).isTrue();
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    assertThat(claims.getIssuer()).isEqualTo(issuer.toString());
    assertThat(claims.getSubject()).isEqualTo(adminId);
    assertThat(claims.getStringClaim("client_id")).isEqualTo(adminId);
    assertThat(claims.getStringClaim("scope")).isEqualTo(Clients.RegistrationScope);
    assertThat(UUID.fromString(claims.getJWTID())).isNotNull();
    assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant())).isEqualTo(tokenLifetime);
  }

  @Test
  public void whenClientRegisteredThenItGetsTokensForItsScopes() throws Exception {
    String secret = register("orders", "orders:read orders:write");

    JsonNode all = json(token("orders", secret, "grant_type=client_credentials"));
    assertThat(all.get("scope").asString()).isEqualTo("orders:read orders:write");
    JsonNode some = json(token("orders", secret, "grant_type=client_credentials&scope=orders%3Aread"));
    assertThat(some.get("scope").asString()).isEqualTo("orders:read");
    assertThat(SignedJWT.parse(some.get("access_token").asString()).getJWTClaimsSet().getStringClaim("scope")).isEqualTo("orders:read");
  }

  @Test
  public void credentialsAreNotInTheEventLog() throws Exception {
    String secret = register("discreet", "a");
    String token = json(token("discreet", secret, "grant_type=client_credentials")).get("access_token").asString();

    String eventLog = eventLog();

    // The event log is readable: the hash of the secret is there
    assertThat(eventLog).contains(Secrets.hash(secret));
    assertThat(eventLog)
        .doesNotContain(secret)
        .doesNotContain(adminSecret)
        .doesNotContain(basic("discreet", secret).substring("Basic ".length()))
        .doesNotContain(token)
        .doesNotContain(adminToken);
  }

  @Test
  public void whenTokenRequestedByAccessTokenSupplierThenItGetsOne() throws Exception {
    String secret = register("supplied", "a b");
    var httpClient = new NettyHttpClient(new NettyHttpClientBuilder().responseTimeout(Duration.ofSeconds(10)).build());
    try (var supplier = new AccessTokenSupplier.Builder(httpClient, uri("/token")).clientCredentials("supplied", secret).scopes(List.of("b")).build()) {
      assertThat(SignedJWT.parse(supplier.get()).getJWTClaimsSet().getStringClaim("scope")).isEqualTo("b");
    }
  }

  @Test
  public void whenScopeExceedsClientThenInvalidScope() throws Exception {
    String secret = register("limited", "a");

    HttpResponse<String> response = token("limited", secret, "grant_type=client_credentials&scope=a+b");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("invalid_scope");
  }

  @Test
  public void whenSecretIsWrongThenInvalidClient() throws Exception {
    assertInvalidClient(token(adminId, "wrong-secret", "grant_type=client_credentials"));
  }

  @Test
  public void whenClientIsUnknownThenInvalidClient() throws Exception {
    assertInvalidClient(token("unknown", adminSecret, "grant_type=client_credentials"));
  }

  @Test
  public void whenNoClientAuthenticationThenInvalidClient() throws Exception {
    assertInvalidClient(post("/token", Map.of("Content-Type", "application/x-www-form-urlencoded"), "grant_type=client_credentials"));
  }

  @Test
  public void whenSecretHashIsForgedThenInvalidClient() throws Exception {
    var headers = Map.of(
        "Content-Type", "application/x-www-form-urlencoded",
        "Authorization", basic(adminId, "wrong-secret"),
        Inbox.SecretHashHeader, Secrets.hash(adminSecret)
    );
    assertInvalidClient(post("/token", headers, "grant_type=client_credentials"));
  }

  @Test
  public void whenSecretInBodyThenInvalidRequest() throws Exception {
    var response = post(
        "/token",
        Map.of("Content-Type", "application/x-www-form-urlencoded"),
        "grant_type=client_credentials&client_id=" + adminId + "&client_secret=" + adminSecret
    );
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("invalid_request");
  }

  @Test
  public void whenGrantTypeIsNotClientCredentialsThenUnsupportedGrantType() throws Exception {
    var response = token(adminId, adminSecret, "grant_type=password&username=a&password=b");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("unsupported_grant_type");
  }

  @Test
  public void whenParameterIsRepeatedThenInvalidRequest() throws Exception {
    var response = token(adminId, adminSecret, "grant_type=client_credentials&scope=a&scope=b");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("invalid_request");
  }

  @Test
  public void whenRegisteringWithoutTokenThenUnauthorized() throws Exception {
    var response = post("/clients", Map.of("Content-Type", "application/json", Inbox.SubjectHeader, adminId), "{\"client_id\":\"x\",\"scope\":\"a\"}");
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
  }

  @Test
  public void whenRegisteringWithInvalidTokenThenUnauthorized() throws Exception {
    String forged = new Keys(Keys.generate(), issuer, null, Clock.systemUTC()).sign(new Tokens.IssuedToken(
        UUID.randomUUID().toString(), adminId, Clients.RegistrationScope, Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 300
    ));
    var response = post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + forged), "{\"client_id\":\"x\",\"scope\":\"a\"}");
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).contains("error=\"invalid_token\""));
  }

  @Test
  public void whenRegisteringWithoutRegistrationScopeThenForbidden() throws Exception {
    String secret = register("ordinary", "a");
    String token = json(token("ordinary", secret, "grant_type=client_credentials")).get("access_token").asString();

    var response = post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + token), "{\"client_id\":\"x\",\"scope\":\"a\"}");

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).contains("error=\"insufficient_scope\""));
  }

  @Test
  public void whenClientIdIsTakenThenConflict() throws Exception {
    String secret = register("taken", "a");

    var response = registration("taken", "b");

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(json(token("taken", secret, "grant_type=client_credentials")).get("scope").asString()).isEqualTo("a");
  }

  @Test
  public void whenRegistrationIsInvalidThenBadRequest() throws Exception {
    assertThat(registration("not valid", "a").statusCode()).isEqualTo(400);
    assertThat(registration("valid", "").statusCode()).isEqualTo(400);
  }

  @Test
  public void whenMetadataRequestedThenItDescribesTheIssuer() throws Exception {
    JsonNode metadata = json(get("/.well-known/oauth-authorization-server"));
    assertThat(metadata.get("issuer").asString()).isEqualTo(issuer.toString());
    assertThat(metadata.get("token_endpoint").asString()).isEqualTo(issuer + "/token");
    assertThat(metadata.get("jwks_uri").asString()).isEqualTo(issuer + "/.well-known/jwks.json");
  }

  @Test
  public void whenHealthRequestedThenOk() throws Exception {
    assertThat(get("/health").statusCode()).isEqualTo(200);
  }

  @Test
  public void whenUnknownPathOrMethodThenNotFoundOrNotAllowed() throws Exception {
    assertThat(get("/unknown").statusCode()).isEqualTo(404);
    assertThat(get("/token").statusCode()).isEqualTo(405);
  }

  private static void assertInvalidClient(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(json(response).get("error").asString()).isEqualTo("invalid_client");
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Basic realm="));
  }

  /// Registers a client and returns its secret
  private static String register(String clientId, String scope) throws IOException, InterruptedException {
    HttpResponse<String> response = registration(clientId, scope);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    JsonNode body = json(response);
    assertThat(body.get("client_id").asString()).isEqualTo(clientId);
    assertThat(body.get("scope").asString()).isEqualTo(scope);
    return body.get("client_secret").asString();
  }

  private static HttpResponse<String> registration(String clientId, String scope) throws IOException, InterruptedException {
    String body = DataType.objectMapper.writeValueAsString(Map.of("client_id", clientId, "scope", scope));
    return post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken), body);
  }

  private static HttpResponse<String> token(String clientId, String secret, String form) throws IOException, InterruptedException {
    return post("/token", Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", basic(clientId, secret)), form);
  }

  private static String basic(String clientId, String secret) {
    String credentials = URLEncoder.encode(clientId, UTF_8) + ":" + URLEncoder.encode(secret, UTF_8);
    return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8));
  }

  /// The data of all the events, as text. Quoted identifiers work in both SQL Server and PostgreSQL.
  private static String eventLog() throws SQLException {
    var eventLog = new StringBuilder();
    try (
        var connection = Init.dataSource().getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT Data FROM \"" + schema + "\".\"Event\"")
    ) {
      while (rows.next()) {
        byte[] data = rows.getBytes(1);
        if (data != null)
          eventLog.append(new String(data, UTF_8)).append('\n');
      }
    }
    return eventLog.toString();
  }

  private static JsonNode json(HttpResponse<String> response) {
    return DataType.objectMapper.readTree(response.body());
  }

  private static URI uri(String path) {
    return URI.create("http://localhost:" + server.port() + path);
  }

  private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri(path)).GET().build());
  }

  private static HttpResponse<String> post(String path, Map<String, String> headers, String body) throws IOException, InterruptedException {
    var request = HttpRequest.newBuilder(uri(path)).POST(BodyPublishers.ofString(body));
    headers.forEach(request::header);
    return send(request.build());
  }

  private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, BodyHandlers.ofString());
    }
  }

}
