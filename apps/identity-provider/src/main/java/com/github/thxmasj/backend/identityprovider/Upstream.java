package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.identityprovider.Authorizations.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/// The upstream OpenID Connect provider users sign in with, as a confidential client of it with the authorization
/// code flow. Used by the [Edge] only: the code of the upstream provider, the client secret and the upstream ID token
/// never enter the event log, only the claims of the user.
///
/// The endpoints are found by OpenID Connect Discovery when the identity provider starts. The user is bound to the
/// browser which started the sign-in by PKCE and the nonce, both derived from a secret the edge keeps in a cookie.
final class Upstream {

  private static final Duration timeout = Duration.ofSeconds(10);

  private final Config config;
  private final URI redirectUri;
  private final URI authorizationEndpoint;
  private final URI tokenEndpoint;
  private final DefaultJWTProcessor<SecurityContext> idTokenProcessor;
  private final HttpClient httpClient;

  /// @param issuer the issuer of the identity provider, whose `/callback` is the redirect URI at the upstream provider
  Upstream(Config config, URI issuer) {
    this.config = config;
    this.redirectUri = URI.create(issuer + "/callback");
    this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    JsonNode metadata = discover(config.issuer());
    this.authorizationEndpoint = URI.create(required(metadata, "authorization_endpoint"));
    this.tokenEndpoint = URI.create(required(metadata, "token_endpoint"));
    JWKSource<SecurityContext> keys;
    try {
      keys = JWKSourceBuilder.create(URI.create(required(metadata, "jwks_uri")).toURL()).build();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException("The jwks_uri of the upstream provider is not a URL", e);
    }
    this.idTokenProcessor = new DefaultJWTProcessor<>();
    idTokenProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256), keys));
    idTokenProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        config.clientId(),
        new JWTClaimsSet.Builder().issuer(config.issuer().toString()).build(),
        Set.of("sub", "iat", "exp", "nonce")
    ));
  }

  /// The upstream provider, as configured by environment variables
  ///
  /// @param issuer   the issuer of the upstream provider, at which its discovery document is found
  /// @param clientId the client id of the identity provider at the upstream provider
  /// @param scope    the scope requested from the upstream provider
  record Config(URI issuer, String clientId, String clientSecret, String scope) {}

  /// Where to send a user to sign in
  ///
  /// @param state  the authorization the user signs in for
  /// @param secret the secret the PKCE verifier and nonce are derived from, kept in the browser of the user
  URI authorizationUrl(String state, String secret) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("response_type", "code");
    parameters.put("client_id", config.clientId());
    parameters.put("redirect_uri", redirectUri.toString());
    parameters.put("scope", config.scope());
    parameters.put("state", state);
    parameters.put("nonce", nonce(secret));
    parameters.put("code_challenge", Secrets.hash(verifier(secret)));
    parameters.put("code_challenge_method", "S256");
    String query = form(parameters);
    String endpoint = authorizationEndpoint.toString();
    return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + query);
  }

  /// The user signed in, by redeeming the code of the upstream provider and verifying the ID token it gives. Fails if
  /// the code is refused or the ID token is invalid.
  Mono<User> signIn(String code, String secret) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("grant_type", "authorization_code");
    parameters.put("code", code);
    parameters.put("redirect_uri", redirectUri.toString());
    parameters.put("code_verifier", verifier(secret));
    String credentials = URLEncoder.encode(config.clientId(), UTF_8) + ":" + URLEncoder.encode(config.clientSecret(), UTF_8);
    var request = HttpRequest.newBuilder(tokenEndpoint)
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("Accept", "application/json")
        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)))
        .POST(BodyPublishers.ofString(form(parameters)))
        .build();
    return Mono.fromFuture(() -> httpClient.sendAsync(request, BodyHandlers.ofString()))
        .flatMap(response -> {
          if (response.statusCode() != 200)
            return Mono.error(new IllegalStateException("The upstream token endpoint responded " + response.statusCode() + ": " + response.body()));
          JsonNode body = DataType.objectMapper.readTree(response.body());
          JsonNode idToken = body.get("id_token");
          if (idToken == null || !idToken.isString())
            return Mono.error(new IllegalStateException("The upstream token response has no id_token"));
          // The keys may be fetched, which blocks
          return Mono.fromCallable(() -> user(idTokenProcessor.process(idToken.asString(), null), secret))
              .subscribeOn(Schedulers.boundedElastic());
        });
  }

  private static User user(JWTClaimsSet claims, String secret) throws java.text.ParseException {
    if (!nonce(secret).equals(claims.getStringClaim("nonce")))
      throw new IllegalStateException("The nonce of the upstream ID token does not match");
    Object authTime = claims.getClaim("auth_time");
    return new User(
        claims.getSubject(),
        claims.getStringClaim("email"),
        claims.getBooleanClaim("email_verified"),
        claims.getStringClaim("name"),
        authTime instanceof Number number ? number.longValue() : null
    );
  }

  /// The PKCE verifier is the secret itself, which is 43 characters of base64url, RFC 7636 section 4.1
  private static String verifier(String secret) {
    return secret;
  }

  /// The nonce can't be derived from the code challenge, which is sent through the browser
  private static String nonce(String secret) {
    return Secrets.hash("nonce:" + secret);
  }

  private JsonNode discover(URI issuer) {
    String base = issuer.toString();
    URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/.well-known/openid-configuration");
    try (var client = HttpClient.newBuilder().connectTimeout(timeout).build()) {
      var response = client.send(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build(), BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("Failed to discover the upstream provider at " + uri + ": status " + response.statusCode());
      JsonNode metadata = DataType.objectMapper.readTree(response.body());
      // OpenID Connect Discovery section 4.3
      if (!issuer.toString().equals(required(metadata, "issuer")))
        throw new IllegalStateException("The issuer of the upstream provider at " + uri + " is " + metadata.get("issuer").asString() + ", not " + issuer);
      return metadata;
    } catch (IOException e) {
      throw new IllegalStateException("Failed to discover the upstream provider at " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while discovering the upstream provider at " + uri, e);
    }
  }

  private static String required(JsonNode metadata, String field) {
    JsonNode value = metadata.get(field);
    if (value == null || !value.isString())
      throw new IllegalStateException("The metadata of the upstream provider has no " + field);
    return value.asString();
  }

  static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
  }

}
