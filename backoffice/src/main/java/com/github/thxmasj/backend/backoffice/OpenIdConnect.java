package com.github.thxmasj.backend.backoffice;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/// The OpenID Connect provider users sign in to the backoffice with, as a client of it with the authorization code
/// flow. The code, the client secret and the ID token never enter the event log, only the claims of the user.
///
/// The endpoints are found by OpenID Connect Discovery when the first user signs in, rather than when the application
/// starts, so the application may be its own provider, as the identity provider is. The user is bound to the browser
/// which started the sign-in by PKCE and the nonce, both derived from a secret kept in a cookie.
final class OpenIdConnect {

  private static final Duration timeout = Duration.ofSeconds(10);
  private static final SecureRandom random = new SecureRandom();

  private final Backoffice.Config config;
  private final URI redirectUri;
  private final HttpClient httpClient;
  private final Mono<Metadata> metadata;

  private record Metadata(URI authorizationEndpoint, URI tokenEndpoint, DefaultJWTProcessor<SecurityContext> idTokenProcessor) {}

  OpenIdConnect(Backoffice.Config config) {
    this.config = config;
    this.redirectUri = URI.create(config.url() + Sessions.Path + "/callback");
    this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    // Discovered once it succeeds, and tried again for the next sign-in if it fails
    this.metadata = Mono.fromCallable(this::discover)
        .subscribeOn(Schedulers.boundedElastic())
        .cacheInvalidateIf(_ -> false)
        .onErrorResume(e -> Mono.error(new IllegalStateException("Failed to discover the provider " + config.issuer(), e)));
  }

  /// Where to send a user to sign in
  ///
  /// @param state  the session the user signs in for
  /// @param secret the secret the PKCE verifier and nonce are derived from, kept in the browser of the user
  Mono<URI> authorizationUrl(String state, String secret) {
    return metadata.map(metadata -> {
      var parameters = new LinkedHashMap<String, String>();
      parameters.put("response_type", "code");
      parameters.put("client_id", config.clientId());
      parameters.put("redirect_uri", redirectUri.toString());
      parameters.put("scope", config.scope());
      parameters.put("state", state);
      parameters.put("nonce", nonce(secret));
      parameters.put("code_challenge", hash(verifier(secret)));
      parameters.put("code_challenge_method", "S256");
      String endpoint = metadata.authorizationEndpoint().toString();
      return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + form(parameters));
    });
  }

  /// The claims of the user who signed in, by redeeming the code and verifying the ID token the provider gives. Fails
  /// if the code is refused or the ID token is invalid.
  Mono<JWTClaimsSet> signIn(String code, String secret) {
    return metadata.flatMap(metadata -> {
      var parameters = new LinkedHashMap<String, String>();
      parameters.put("grant_type", "authorization_code");
      parameters.put("code", code);
      parameters.put("redirect_uri", redirectUri.toString());
      parameters.put("code_verifier", verifier(secret));
      var request = HttpRequest.newBuilder(metadata.tokenEndpoint())
          .timeout(timeout)
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("Accept", "application/json");
      if (config.clientSecret() != null) {
        // HTTP Basic, which RFC 6749 section 2.3.1 requires every provider to support
        String credentials = URLEncoder.encode(config.clientId(), UTF_8) + ":" + URLEncoder.encode(config.clientSecret(), UTF_8);
        request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)));
      } else {
        // A public client, RFC 6749 section 4.1.3
        parameters.put("client_id", config.clientId());
      }
      var built = request.POST(BodyPublishers.ofString(form(parameters))).build();
      return Mono.fromFuture(() -> httpClient.sendAsync(built, BodyHandlers.ofString()))
          .flatMap(response -> {
            if (response.statusCode() != 200)
              return Mono.error(new IllegalStateException("The token endpoint responded " + response.statusCode() + ": " + response.body()));
            JsonNode body = DataType.objectMapper.readTree(response.body());
            JsonNode idToken = body.get("id_token");
            if (idToken == null || !idToken.isString())
              return Mono.error(new IllegalStateException("The token response has no id_token"));
            // The keys may be fetched, which blocks
            return Mono.fromCallable(() -> verified(metadata.idTokenProcessor().process(idToken.asString(), null), secret))
                .subscribeOn(Schedulers.boundedElastic());
          });
    });
  }

  private static JWTClaimsSet verified(JWTClaimsSet claims, String secret) throws ParseException {
    if (!nonce(secret).equals(claims.getStringClaim("nonce")))
      throw new IllegalStateException("The nonce of the ID token does not match");
    return claims;
  }

  /// A new secret for a sign-in, of 256 bits
  static String generateSecret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /// The SHA-256 hash of a secret, base64url encoded. The secrets have 256 bits of entropy, so a fast hash is enough.
  static String hash(String secret) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /// The PKCE verifier is the secret itself, which is 43 characters of base64url, RFC 7636 section 4.1
  private static String verifier(String secret) {
    return secret;
  }

  /// The nonce can't be derived from the code challenge, which is sent through the browser
  private static String nonce(String secret) {
    return hash("nonce:" + secret);
  }

  private Metadata discover() {
    String base = config.issuer().toString();
    URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/.well-known/openid-configuration");
    JsonNode metadata;
    try {
      var response = httpClient.send(HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json").GET().build(), BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IllegalStateException("Failed to discover the provider at " + uri + ": status " + response.statusCode());
      metadata = DataType.objectMapper.readTree(response.body());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to discover the provider at " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while discovering the provider at " + uri, e);
    }
    // OpenID Connect Discovery section 4.3
    if (!config.issuer().toString().equals(required(metadata, "issuer")))
      throw new IllegalStateException("The issuer of the provider at " + uri + " is " + metadata.get("issuer").asString() + ", not " + config.issuer());
    var idTokenProcessor = new DefaultJWTProcessor<SecurityContext>();
    var keys = new Keys(URI.create(required(metadata, "jwks_uri")));
    idTokenProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256), keys));
    idTokenProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        config.clientId(),
        new JWTClaimsSet.Builder().issuer(config.issuer().toString()).build(),
        Set.of("sub", "iat", "exp", "nonce")
    ));
    return new Metadata(
        URI.create(required(metadata, "authorization_endpoint")),
        URI.create(required(metadata, "token_endpoint")),
        idTokenProcessor
    );
  }

  /// The keys of the provider, fetched with the HTTP client of the JDK rather than by `java.net.URL`, whose http and
  /// https protocols are not enabled in the native image. They are fetched again for a key id not among them, as the
  /// provider may have rotated its keys, but at most every 30 seconds.
  private final class Keys implements JWKSource<SecurityContext> {

    private static final Duration minRefetchInterval = Duration.ofSeconds(30);

    private final URI jwksUri;
    private JWKSet keys;
    private Instant fetchedAt;

    Keys(URI jwksUri) {
      this.jwksUri = jwksUri;
    }

    @Override
    public synchronized List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
      if (keys != null) {
        List<JWK> selected = selector.select(keys);
        if (!selected.isEmpty() || Instant.now().isBefore(fetchedAt.plus(minRefetchInterval)))
          return selected;
      }
      keys = fetch();
      fetchedAt = Instant.now();
      return selector.select(keys);
    }

    private JWKSet fetch() throws KeySourceException {
      try {
        var response = httpClient.send(HttpRequest.newBuilder(jwksUri).timeout(timeout).header("Accept", "application/json").GET().build(), BodyHandlers.ofString());
        if (response.statusCode() != 200)
          throw new KeySourceException("Failed to fetch the keys of the provider from " + jwksUri + ": status " + response.statusCode());
        return JWKSet.parse(response.body());
      } catch (IOException | ParseException e) {
        throw new KeySourceException("Failed to fetch the keys of the provider from " + jwksUri, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new KeySourceException("Interrupted while fetching the keys of the provider", e);
      }
    }

  }

  private static String required(JsonNode metadata, String field) {
    JsonNode value = metadata.get(field);
    if (value == null || !value.isString())
      throw new IllegalStateException("The metadata of the provider has no " + field);
    return value.asString();
  }

  static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
  }

  /// The parameters of a query or form, of which the first of each name is kept. Fails on an invalid encoding.
  static Map<String, String> parse(String query) {
    var parameters = new LinkedHashMap<String, String>();
    if (query == null || query.isEmpty())
      return parameters;
    for (String pair : query.split("&")) {
      if (pair.isEmpty())
        continue;
      int i = pair.indexOf('=');
      String name = java.net.URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), UTF_8);
      String value = i < 0 ? "" : java.net.URLDecoder.decode(pair.substring(i + 1), UTF_8);
      parameters.putIfAbsent(name, value);
    }
    return parameters;
  }

}
