package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.http.AccessTokenSupplier;
import com.github.thxmasj.backend.core.http.NettyHttpClient;
import com.github.thxmasj.backend.core.http.NettyHttpClientBuilder;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.http.inbox.Form;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.identityprovider.Config.BootstrapClient;
import com.github.thxmasj.backend.identityprovider.ImportedClients.ImportedClient;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.Audience;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.UserInfoRequest;
import com.nimbusds.openid.connect.sdk.UserInfoResponse;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import reactor.netty.DisposableServer;
import tools.jackson.databind.JsonNode;

/// Runs the identity provider against the test database, with a bootstrapped client registering the other clients,
/// and users signing in to web apps with a stub upstream provider.
@Tag("database")
public class IdentityProviderTest {

  /// The URL the identity provider is served at, so the OpenID Connect client finds it by discovery
  private static URI issuer;
  private static final URI appRedirectUri = URI.create("https://app.example.com/callback");
  private static UpstreamStub upstream;
  /// The values of the cookies the identity provider has set, to check that they don't enter the event log
  private static final List<String> cookieValues = new java.util.concurrent.CopyOnWriteArrayList<>();
  /// The authorization codes and tokens of the users signed in, to check that they don't enter the event log
  private static final List<String> userCredentials = new java.util.concurrent.CopyOnWriteArrayList<>();
  private static final String adminId = "admin";
  private static final String adminSecret = "the-secret-of-the-bootstrap-client";
  private static final Duration tokenLifetime = Duration.ofMinutes(5);
  /// An imported client with a hash made by Spring Security's BCryptPasswordEncoder when the test starts
  private static final String partnerSecret = "Please_hide_me!";
  private static final String partnerSubject = "merchantaggregator:6f1c2a9e-3b7d-4e58-9a0c-2d4b8e1f7a63";
  /// An imported client with a hash made once by BCryptPasswordEncoder
  private static final String legacySecret = "A_fixed_secret_of_an_imported_client";
  private static final String legacyHash = "$2a$10$Uekj8xXissq6Wy3fUSUOW.epdfkVAf3.uY6/DkL8L43gfnvmTrB82";
  private static final String legacySubject = "https://wallet.example.com/provider/example";

  private static final String schema = UUID.randomUUID().toString();
  private static StateMachine stateMachine;
  private static DisposableServer server;
  private static String adminToken;
  private static String partnerHash;
  private static List<ImportedClient> importedClients;

  @BeforeAll
  public static void setUp() throws Exception {
    stateMachine = Init.stateMachine(schema, Inbox.transitions(tokenLifetime));
    IdentityProvider.bootstrap(stateMachine, new BootstrapClient(adminId, adminSecret));
    partnerHash = new BCryptPasswordEncoder().encode(partnerSecret);
    assertThat(partnerHash).startsWith("$2a$10$");
    importedClients = importedClients(partnerHash);
    IdentityProvider.importClients(stateMachine, importedClients);
    upstream = new UpstreamStub();
    int port;
    try (var socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    issuer = URI.create("http://localhost:" + port);
    var upstreamConfig = new Config.Upstream(URI.create(upstream.issuer()), UpstreamStub.clientId, UpstreamStub.clientSecret, "openid email profile");
    server = IdentityProvider.serve(stateMachine, new Keys(Keys.generate(), issuer, null, Clock.systemUTC()), IdentityProvider.upstream(upstreamConfig, issuer), port, importedClients);
    adminToken = json(token(adminId, adminSecret, "grant_type=client_credentials")).get("access_token").asString();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    server.disposeNow();
    upstream.close();
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
  public void whenClientRegisteredWithSubjectThenItIsTheSubjectOfItsTokens() throws Exception {
    String subject = "merchantaggregator:6f1c2a9e-3b7d-4e58-9a0c-2d4b8e1f7a63";
    HttpResponse<String> response = registration("aggregator", "a", subject, adminToken);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(json(response).get("subject").asString()).isEqualTo(subject);

    JWTClaimsSet claims = claims(json(token("aggregator", json(response).get("client_secret").asString(), "grant_type=client_credentials")));

    assertThat(claims.getSubject()).isEqualTo(subject);
    assertThat(claims.getStringClaim("client_id")).isEqualTo("aggregator");
  }

  @Test
  public void whenClientRegisteredWithoutSubjectThenClientIdIsTheSubjectOfItsTokens() throws Exception {
    HttpResponse<String> response = registration("subjectless", "a");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(json(response).has("subject")).isFalse();

    JWTClaimsSet claims = claims(json(token("subjectless", json(response).get("client_secret").asString(), "grant_type=client_credentials")));

    assertThat(claims.getSubject()).isEqualTo("subjectless");
    assertThat(claims.getStringClaim("client_id")).isEqualTo("subjectless");
  }

  @Test
  public void whenSubjectIsInvalidThenBadRequest() throws Exception {
    for (String subject : List.of("", "   ", "a\nb", "a\u0000b", "x".repeat(Clients.maxSubjectLength + 1)))
      assertThat(registration("invalid-subject", "a", subject, adminToken).statusCode()).as("subject %s", subject).isEqualTo(400);
    for (String body : List.of(
        "{\"client_id\":\"invalid-subject\",\"scope\":\"a\",\"subject\":null}",
        "{\"client_id\":\"invalid-subject\",\"scope\":\"a\",\"subject\":42}"
    ))
      assertThat(post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken), body).statusCode()).as(body).isEqualTo(400);
    // The client id is not taken by the rejected registrations
    assertThat(registration("invalid-subject", "a", "x".repeat(Clients.maxSubjectLength), adminToken).statusCode()).isEqualTo(201);
  }

  @Test
  public void whenRegistrarHasSubjectThenItRegistersClientsAsItsClientId() throws Exception {
    String subject = "https://wallet.example.com/provider/registrar";
    HttpResponse<String> registered = registration("registrar", Clients.RegistrationScope, subject, adminToken);
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    JsonNode tokenResponse = json(token("registrar", json(registered).get("client_secret").asString(), "grant_type=client_credentials"));
    assertThat(claims(tokenResponse).getSubject()).isEqualTo(subject);

    HttpResponse<String> response = registration("registered-by-registrar", "a", null, tokenResponse.get("access_token").asString());

    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    assertThat(eventLog()).contains("\"registeredBy\":\"registrar\"");
  }

  @Test
  public void whenImportedClientAuthenticatesThenItGetsTokensWithItsSubjectAndScopes() throws Exception {
    HttpResponse<String> response = token("partner", partnerSecret, "grant_type=client_credentials");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(json(response).get("scope").asString()).isEqualTo("payments:read payments:write");
    JWTClaimsSet claims = claims(json(response));
    assertThat(claims.getSubject()).isEqualTo(partnerSubject);
    assertThat(claims.getStringClaim("client_id")).isEqualTo("partner");
    assertThat(claims.getStringClaim("scope")).isEqualTo("payments:read payments:write");
  }

  @Test
  public void whenImportedClientHasFixedSpringHashAndNoScopeThenItGetsTokensWithoutScopes() throws Exception {
    HttpResponse<String> response = token("legacy", legacySecret, "grant_type=client_credentials");

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JWTClaimsSet claims = claims(json(response));
    assertThat(claims.getSubject()).isEqualTo(legacySubject);
    assertThat(claims.getStringClaim("client_id")).isEqualTo("legacy");
    assertThat(claims.getStringClaim("scope")).isEmpty();
    assertThat(token("legacy", legacySecret, "grant_type=client_credentials&scope=a").statusCode()).isEqualTo(400);
  }

  @Test
  public void whenImportedClientHasWrongSecretThenInvalidClient() throws Exception {
    assertInvalidClient(token("partner", "wrong-secret", "grant_type=client_credentials"));
    assertInvalidClient(token("legacy", partnerSecret, "grant_type=client_credentials"));
    // The BCrypt hash is not a secret
    assertInvalidClient(token("partner", partnerHash, "grant_type=client_credentials"));
    assertInvalidClient(token("legacy", legacyHash, "grant_type=client_credentials"));
  }

  @Test
  public void whenImportedAgainThenRegisteredClientsAreKept() throws Exception {
    String otherHash = new BCryptPasswordEncoder().encode("another-secret");

    assertThatNoException().isThrownBy(() -> IdentityProvider.importClients(stateMachine, importedClients(otherHash)));
    assertThatNoException().isThrownBy(() -> IdentityProvider.importClients(stateMachine, importedClients));

    assertThat(token("partner", partnerSecret, "grant_type=client_credentials").statusCode()).isEqualTo(200);
    assertThat(token("legacy", legacySecret, "grant_type=client_credentials").statusCode()).isEqualTo(200);
  }

  /// As when replicas start at the same time: one registers each client, and the others find its client id taken
  @Test
  public void whenImportedConcurrentlyThenEachClientIsRegisteredOnce() throws Exception {
    var clients = ImportedClients.parse(DataType.objectMapper.writeValueAsString(
        java.util.stream.IntStream.range(0, 5).mapToObj(i -> Map.of("client_id", "concurrent-" + i, "secret_hash", legacyHash)).toList()
    ), "test");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var imports = java.util.stream.IntStream.range(0, 4)
          .mapToObj(_ -> executor.submit(() -> IdentityProvider.importClients(stateMachine, clients)))
          .toList();
      for (var i : imports)
        i.get();
    }
    for (int i = 0; i < 5; i++)
      assertThat(registration("concurrent-" + i, "a").statusCode()).isEqualTo(409);
  }

  @Test
  public void credentialsAreNotInTheEventLog() throws Exception {
    String secret = register("discreet", "a");
    String token = json(token("discreet", secret, "grant_type=client_credentials")).get("access_token").asString();
    String webAppSecret = registerWebApp("discreet-app");
    String verifier = Secrets.generate();
    String code = signIn("discreet-app", "openid email profile", verifier);
    JsonNode userTokens = json(redeem("discreet-app", webAppSecret, code, verifier));
    assertThat(userInfo(userTokens.get("access_token").asString()).statusCode()).isEqualTo(200);

    String eventLog = eventLog();

    // The claims of the user are there
    assertThat(eventLog).contains("ada@example.com");
    for (String credential : List.of(code, verifier, webAppSecret, userTokens.get("access_token").asString(), userTokens.get("id_token").asString(), UpstreamStub.clientSecret))
      assertThat(eventLog).doesNotContain(credential);
    assertThat(cookieValues).isNotEmpty();
    for (String credential : cookieValues)
      assertThat(eventLog).doesNotContain(credential);
    for (String credential : upstream.issuedCodes)
      assertThat(eventLog).doesNotContain(credential);
    for (String credential : userCredentials)
      assertThat(eventLog).doesNotContain(credential);

    // The event log is readable: the hash of the secret is there
    assertThat(eventLog).contains(Secrets.hash(secret));
    assertThat(eventLog)
        .doesNotContain(secret)
        .doesNotContain(adminSecret)
        .doesNotContain(basic("discreet", secret).substring("Basic ".length()))
        .doesNotContain(token)
        .doesNotContain(adminToken)
        .doesNotContain(partnerSecret)
        .doesNotContain(partnerHash)
        .doesNotContain(legacySecret)
        .doesNotContain(legacyHash);
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

  /// Many refused token requests may be an attempt to guess a secret, so each one is a warning for monitoring
  @Test
  public void whenTokenIsRefusedThenItsEventIsAWarning() {
    var refusals = Tokens.transitions(tokenLifetime).get(Tokens.TokenRequest).get(EntityModel.Begin).stream()
        .flatMap(transition -> transition.filters().stream())
        .map(filter -> filter.alternative().model())
        .filter(chosen -> chosen.eventType().equals(Tokens.TokenRefused))
        .toList();

    assertThat(refusals).isNotEmpty().allSatisfy(refusal -> assertThat(refusal.severity()).isEqualTo(Severity.Warning));
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
    var response = post("/clients", Map.of("Content-Type", "application/json", Inbox.RegisteredByHeader, adminId), "{\"client_id\":\"x\",\"scope\":\"a\"}");
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).startsWith("Bearer"));
  }

  @Test
  public void whenRegisteringWithInvalidTokenThenUnauthorized() throws Exception {
    String forged = new Keys(Keys.generate(), issuer, null, Clock.systemUTC()).sign(new Tokens.IssuedToken(
        UUID.randomUUID().toString(), adminId, adminId, Clients.RegistrationScope, Instant.now().getEpochSecond(), Instant.now().getEpochSecond() + 300
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
  public void whenUserSignsInWithOpenIdConnectClientThenItGetsIdTokenAndUserInfo() throws Exception {
    var clientId = new ClientID("webapp");
    var secret = new Secret(registerWebApp(clientId.getValue()));
    upstream.signInAs(new UpstreamStub.User("upstream-ada", "ada@example.com", "Ada Lovelace"));
    // The client is configured with the issuer only, and finds the endpoints by discovery
    var provider = OIDCProviderMetadata.resolve(new Issuer(issuer));
    var state = new State();
    var nonce = new Nonce();
    var verifier = new CodeVerifier();
    var authenticationRequest = new AuthenticationRequest.Builder(ResponseType.CODE, new Scope("openid", "email", "profile"), clientId, appRedirectUri)
        .endpointURI(provider.getAuthorizationEndpointURI())
        .state(state)
        .nonce(nonce)
        .codeChallenge(verifier, CodeChallengeMethod.S256)
        .build();

    URI redirect = browse(authenticationRequest.toURI()).location();

    var authorizationResponse = AuthorizationResponse.parse(redirect);
    assertThat(authorizationResponse.indicatesSuccess()).as(redirect.toString()).isTrue();
    assertThat(authorizationResponse.getState()).isEqualTo(state);
    var code = authorizationResponse.toSuccessResponse().getAuthorizationCode();
    var tokenRequest = new TokenRequest.Builder(provider.getTokenEndpointURI(), new ClientSecretBasic(clientId, secret), new AuthorizationCodeGrant(code, appRedirectUri, verifier)).build();
    var tokenResponse = OIDCTokenResponseParser.parse(tokenRequest.toHTTPRequest().send());
    assertThat(tokenResponse.indicatesSuccess()).as(() -> tokenResponse.toErrorResponse().getErrorObject().toString()).isTrue();
    var tokens = ((OIDCTokenResponse) tokenResponse.toSuccessResponse()).getOIDCTokens();
    var idTokenValidator = new IDTokenValidator(provider.getIssuer(), clientId, JWSAlgorithm.RS256, provider.getJWKSetURI().toURL());
    var claims = idTokenValidator.validate(tokens.getIDToken(), nonce);
    var userInfoResponse = UserInfoResponse.parse(new UserInfoRequest(provider.getUserInfoEndpointURI(), tokens.getBearerAccessToken()).toHTTPRequest().send());
    userCredentials.addAll(List.of(code.getValue(), tokens.getIDTokenString(), tokens.getAccessToken().getValue()));

    assertThat(claims.getSubject().getValue()).isEqualTo("upstream-ada");
    assertThat(claims.getAudience()).containsExactly(new Audience(clientId));
    assertThat(claims.getAuthenticationTime()).isNotNull();
    assertThat(claims.getStringClaim("email")).isEqualTo("ada@example.com");
    assertThat(claims.getStringClaim("name")).isEqualTo("Ada Lovelace");
    assertThat(userInfoResponse.indicatesSuccess()).isTrue();
    var userInfo = userInfoResponse.toSuccessResponse().getUserInfo();
    assertThat(userInfo.getSubject().getValue()).isEqualTo("upstream-ada");
    assertThat(userInfo.getEmailAddress()).isEqualTo("ada@example.com");
    assertThat(userInfo.getEmailVerified()).isTrue();
    assertThat(userInfo.getName()).isEqualTo("Ada Lovelace");
    assertThat(SignedJWT.parse(tokens.getAccessToken().getValue()).getJWTClaimsSet().getStringClaim("scope")).isEqualTo("openid email profile");
  }

  @Test
  public void whenOnlyOpenidIsRequestedThenUserHasOnlySubject() throws Exception {
    String secret = registerWebApp("subject-only");

    JsonNode tokens = json(redeem("subject-only", secret, signIn("subject-only", "openid", null), null));

    JWTClaimsSet idToken = SignedJWT.parse(tokens.get("id_token").asString()).getJWTClaimsSet();
    assertThat(idToken.getClaims()).doesNotContainKeys("email", "email_verified", "name");
    JsonNode userInfo = json(userInfo(tokens.get("access_token").asString()));
    assertThat(userInfo.propertyNames()).containsExactly("sub");
  }

  @Test
  public void whenCodeIsRedeemedTwiceThenInvalidGrant() throws Exception {
    String secret = registerWebApp("twice");
    String code = signIn("twice", "openid email", null);

    assertThat(redeem("twice", secret, code, null).statusCode()).isEqualTo(200);
    assertInvalidGrant(redeem("twice", secret, code, null));
  }

  @Test
  public void whenCodeVerifierIsWrongOrMissingThenInvalidGrantAndCodeIsUsedUp() throws Exception {
    String secret = registerWebApp("pkce");
    String verifier = Secrets.generate();

    String code = signIn("pkce", "openid", verifier);
    assertInvalidGrant(redeem("pkce", secret, code, Secrets.generate()));
    assertInvalidGrant(redeem("pkce", secret, code, verifier));

    String another = signIn("pkce", "openid", verifier);
    assertInvalidGrant(redeem("pkce", secret, another, null));
  }

  @Test
  public void whenCodeIsRedeemedByAnotherClientOrRedirectUriThenInvalidGrant() throws Exception {
    String secret = registerWebApp("owner");
    String otherSecret = registerWebApp("thief");

    assertInvalidGrant(redeem("thief", otherSecret, signIn("owner", "openid", null), null));
    var response = post(
        "/token",
        Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", basic("owner", secret)),
        "grant_type=authorization_code&code=" + signIn("owner", "openid", null) + "&redirect_uri=" + URLEncoder.encode("https://app.example.com/other", UTF_8)
    );
    assertInvalidGrant(response);
  }

  @Test
  public void whenCodeIsRedeemedWithWrongSecretThenInvalidClientAndCodeIsKept() throws Exception {
    String secret = registerWebApp("careful");
    String code = signIn("careful", "openid", null);

    assertInvalidClient(redeem("careful", "wrong-secret", code, null));
    assertThat(redeem("careful", secret, code, null).statusCode()).isEqualTo(200);
  }

  @Test
  public void whenClientOrRedirectUriIsInvalidThenErrorPageWithoutRedirect() throws Exception {
    registerWebApp("strict");

    for (URI request : List.of(
        authorizeUri("unknown-client", appRedirectUri.toString(), "openid", "s", null),
        authorizeUri("strict", "https://evil.example.com/callback", "openid", "s", null),
        authorizeUri("strict", null, "openid", "s", null),
        URI.create(issuer + "/authorize?response_type=code&redirect_uri=" + URLEncoder.encode(appRedirectUri.toString(), UTF_8))
    )) {
      Landing landing = browse(request);
      assertThat(landing.location()).as(request.toString()).isNull();
      assertThat(landing.page().statusCode()).as(request.toString()).isEqualTo(400);
      assertThat(landing.page().body()).startsWith("Sign-in failed");
    }
  }

  @Test
  public void whenRequestIsInvalidThenClientIsRedirectedWithError() throws Exception {
    registerWebApp("redirected");
    register("no-sign-in", "a");

    assertRedirectedWithError(authorizeUri("redirected", appRedirectUri.toString(), "email", "s1", null), "invalid_scope", "s1");
    assertRedirectedWithError(URI.create(authorizeUri("redirected", appRedirectUri.toString(), "openid", "s2", null) + "&code_challenge=abc&code_challenge_method=plain"), "invalid_request", "s2");
    assertRedirectedWithError(URI.create(authorizeUri("redirected", appRedirectUri.toString(), "openid", "s3", null) + "&prompt=none"), "login_required", "s3");
    assertRedirectedWithError(URI.create(authorizeUri("redirected", appRedirectUri.toString(), "openid", "s4", null).toString().replace("response_type=code", "response_type=token")), "unsupported_response_type", "s4");
  }

  @Test
  public void whenClientMayNotUseAGrantThenUnauthorizedClient() throws Exception {
    String webAppSecret = registerWebApp("web-only");
    HttpResponse<String> registered = post(
        "/clients",
        Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken),
        "{\"client_id\":\"service-and-app\",\"grant_types\":[\"client_credentials\"],\"redirect_uris\":[]}"
    );
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);

    var response = token("web-only", webAppSecret, "grant_type=client_credentials");
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("unauthorized_client");
    // A client of the client credentials grant can't sign users in, which it is told at its redirect URI only if it
    // has one, which it doesn't
    Landing landing = browse(authorizeUri("service-and-app", appRedirectUri.toString(), "openid", "s", null));
    assertThat(landing.page().statusCode()).isEqualTo(400);
  }

  @Test
  public void whenUserCancelsAtUpstreamThenClientIsRedirectedWithAccessDenied() throws Exception {
    registerWebApp("cancelled");
    upstream.failWith("access_denied");

    assertRedirectedWithError(authorizeUri("cancelled", appRedirectUri.toString(), "openid", "s", null), "access_denied", "s");
  }

  @Test
  public void whenCallbackHasNoCookieThenErrorPage() throws Exception {
    registerWebApp("cookieless");
    // The upstream provider redirects to the callback, which is called by another browser, without the cookie
    var first = send(HttpRequest.newBuilder(authorizeUri("cookieless", appRedirectUri.toString(), "openid", "s", null)).GET().build());
    assertThat(first.statusCode()).isEqualTo(302);
    var atUpstream = send(HttpRequest.newBuilder(URI.create(first.headers().firstValue("Location").orElseThrow())).GET().build());
    var callback = URI.create(atUpstream.headers().firstValue("Location").orElseThrow());

    var response = send(HttpRequest.newBuilder(callback).GET().build());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("another browser");
  }

  @Test
  public void whenUserInfoIsRequestedWithTokenOfNoUserThenRefused() throws Exception {
    assertThat(userInfo(adminToken).statusCode()).isEqualTo(403);
    String secret = register("openid-service", "openid");
    String serviceToken = json(token("openid-service", secret, "grant_type=client_credentials")).get("access_token").asString();
    var response = userInfo(serviceToken);
    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v).contains("invalid_token"));
    assertThat(send(HttpRequest.newBuilder(uri("/userinfo")).GET().build()).statusCode()).isEqualTo(401);
  }

  @Test
  public void whenWebAppIsRegisteredThenItsGrantAndRedirectUrisAreValidated() throws Exception {
    HttpResponse<String> registered = post(
        "/clients",
        Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken),
        "{\"client_id\":\"described\",\"grant_types\":[\"authorization_code\"],\"redirect_uris\":[\"https://app.example.com/callback\"]}"
    );
    assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
    JsonNode body = json(registered);
    assertThat(body.get("grant_types").toString()).isEqualTo("[\"authorization_code\"]");
    assertThat(body.get("response_types").toString()).isEqualTo("[\"code\"]");
    assertThat(body.get("redirect_uris").toString()).isEqualTo("[\"https://app.example.com/callback\"]");

    for (String invalid : List.of(
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[\"authorization_code\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[\"password\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[],\"redirect_uris\":[]}",
        "{\"client_id\":\"invalid-grant\",\"redirect_uris\":[\"https://app.example.com/callback\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[\"authorization_code\"],\"redirect_uris\":[\"http://app.example.com/callback\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[\"authorization_code\"],\"redirect_uris\":[\"https://app.example.com/callback#fragment\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":[\"authorization_code\"],\"redirect_uris\":[\"/callback\"]}",
        "{\"client_id\":\"invalid-grant\",\"grant_types\":\"authorization_code\",\"redirect_uris\":[\"https://app.example.com/callback\"]}"
    ))
      assertThat(post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken), invalid).statusCode()).as(invalid).isEqualTo(400);
    assertThat(post(
        "/clients",
        Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken),
        "{\"client_id\":\"local-app\",\"grant_types\":[\"authorization_code\"],\"redirect_uris\":[\"http://localhost:3000/callback\"]}"
    ).statusCode()).isEqualTo(201);
  }

  @Test
  public void whenOpenIdConfigurationRequestedThenItDescribesTheProvider() throws Exception {
    JsonNode metadata = json(get("/.well-known/openid-configuration"));
    assertThat(metadata.get("issuer").asString()).isEqualTo(issuer.toString());
    assertThat(metadata.get("authorization_endpoint").asString()).isEqualTo(issuer + "/authorize");
    assertThat(metadata.get("userinfo_endpoint").asString()).isEqualTo(issuer + "/userinfo");
    assertThat(metadata.get("response_types_supported").toString()).isEqualTo("[\"code\"]");
    assertThat(metadata.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
    assertThat(json(get("/.well-known/oauth-authorization-server"))).isEqualTo(metadata);
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

  /// Registers a web app which signs users in at the redirect URI, and returns its secret
  private static String registerWebApp(String clientId) throws IOException, InterruptedException {
    var body = Map.of("client_id", clientId, "grant_types", List.of("authorization_code"), "redirect_uris", List.of(appRedirectUri.toString()));
    HttpResponse<String> response = post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + adminToken), DataType.objectMapper.writeValueAsString(body));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return json(response).get("client_secret").asString();
  }

  private static URI authorizeUri(String clientId, String redirectUri, String scope, String state, String verifier) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("response_type", "code");
    parameters.put("client_id", clientId);
    if (redirectUri != null)
      parameters.put("redirect_uri", redirectUri);
    parameters.put("scope", scope);
    parameters.put("state", state);
    parameters.put("nonce", "n-" + state);
    if (verifier != null) {
      parameters.put("code_challenge", Secrets.hash(verifier));
      parameters.put("code_challenge_method", "S256");
    }
    return URI.create(issuer + "/authorize?" + Form.encode(parameters));
  }

  /// Signs the user in to the client, and returns the code the client gets
  private static String signIn(String clientId, String scope, String verifier) throws IOException, InterruptedException {
    Landing landing = browse(authorizeUri(clientId, appRedirectUri.toString(), scope, "a-state", verifier));
    assertThat(landing.location()).as(() -> landing.page().body()).isNotNull();
    Map<String, String> parameters = Form.parse(landing.location().getRawQuery().getBytes(UTF_8));
    assertThat(parameters).containsEntry("state", "a-state").containsKey("code");
    userCredentials.add(parameters.get("code"));
    return parameters.get("code");
  }

  private static HttpResponse<String> redeem(String clientId, String secret, String code, String verifier) throws IOException, InterruptedException {
    String form = "grant_type=authorization_code&code=" + code + "&redirect_uri=" + URLEncoder.encode(appRedirectUri.toString(), UTF_8)
        + (verifier != null ? "&code_verifier=" + verifier : "");
    return token(clientId, secret, form);
  }

  private static HttpResponse<String> userInfo(String token) throws IOException, InterruptedException {
    return send(HttpRequest.newBuilder(uri("/userinfo")).header("Authorization", "Bearer " + token).GET().build());
  }

  private static void assertInvalidGrant(HttpResponse<String> response) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    assertThat(json(response).get("error").asString()).isEqualTo("invalid_grant");
  }

  private static void assertRedirectedWithError(URI request, String error, String state) throws IOException, InterruptedException {
    Landing landing = browse(request);
    assertThat(landing.location()).as(request.toString()).isNotNull();
    assertThat(landing.location().toString()).startsWith(appRedirectUri.toString());
    Map<String, String> parameters = Form.parse(landing.location().getRawQuery().getBytes(UTF_8));
    assertThat(parameters).containsEntry("error", error).containsEntry("state", state).doesNotContainKey("code");
  }

  /// Where a browser ends up: redirected to the app, or shown a page
  private record Landing(URI location, HttpResponse<String> page) {}

  /// Follows the redirects from the URI as a browser does, with the cookies of the identity provider, until it is
  /// redirected to the app or shown a page
  private static Landing browse(URI uri) throws IOException, InterruptedException {
    var cookies = new LinkedHashMap<String, String>();
    for (int hops = 0; hops < 10; hops++) {
      var request = HttpRequest.newBuilder(uri).GET();
      if (uri.getPort() == issuer.getPort() && !cookies.isEmpty())
        request.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
      HttpResponse<String> response = send(request.build());
      for (String setCookie : response.headers().allValues("Set-Cookie")) {
        String pair = setCookie.split(";")[0];
        String name = pair.substring(0, pair.indexOf('='));
        String value = pair.substring(pair.indexOf('=') + 1);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/callback");
        if (setCookie.contains("Max-Age=0")) {
          cookies.remove(name);
        } else {
          cookies.put(name, value);
          cookieValues.add(value);
        }
      }
      if (response.statusCode() != 302)
        return new Landing(null, response);
      URI location = uri.resolve(response.headers().firstValue("Location").orElseThrow());
      if (location.getHost().equals(appRedirectUri.getHost()))
        return new Landing(location, null);
      uri = location;
    }
    throw new AssertionError("Too many redirects");
  }

  private static List<ImportedClient> importedClients(String partnerHash) {
    var clients = List.of(
        Map.of("client_id", "partner", "secret_hash", partnerHash, "subject", partnerSubject, "scope", "payments:read payments:write", "client_name", "A partner"),
        Map.of("client_id", "legacy", "secret_hash", legacyHash, "subject", legacySubject)
    );
    return ImportedClients.parse(DataType.objectMapper.writeValueAsString(clients), "test");
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
    return registration(clientId, scope, null, adminToken);
  }

  private static HttpResponse<String> registration(String clientId, String scope, String subject, String token) throws IOException, InterruptedException {
    var fields = new LinkedHashMap<String, String>();
    fields.put("client_id", clientId);
    fields.put("scope", scope);
    if (subject != null)
      fields.put("subject", subject);
    String body = DataType.objectMapper.writeValueAsString(fields);
    return post("/clients", Map.of("Content-Type", "application/json", "Authorization", "Bearer " + token), body);
  }

  private static JWTClaimsSet claims(JsonNode tokenResponse) throws ParseException {
    return SignedJWT.parse(tokenResponse.get("access_token").asString()).getJWTClaimsSet();
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
