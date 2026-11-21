package com.github.thxmasj.backend.http.inbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

/// A client of an OpenID Connect provider, signing users in with the authorization code flow, e.g. with Microsoft
/// Entra ID or the identity provider. It builds the requests and verifies the responses, while the application
/// keeps the state of the sign-in, e.g. in a cookie.
///
/// The endpoints of the provider are found by OpenID Connect Discovery when first needed, rather than when the client
/// is created, so an application may be a client of itself. [#discover()] discovers them at once, to fail early. The
/// user is bound to the browser which started the sign-in by PKCE (S256) and a nonce, both derived from a secret the
/// application keeps in the browser, e.g. in a cookie. The ID token is verified with the keys of the provider, which
/// are fetched again for a key not among them, as the provider may have rotated its keys, but at most every 30
/// seconds.
public final class OpenIdConnectClient {

  private final Config config;
  private final HttpClient httpClient;
  private final Mono<Provider> provider;

  /// The client's registration at the provider
  ///
  /// @param issuer       the issuer of the provider, at which its discovery document is found
  /// @param clientId     the client id at the provider
  /// @param clientSecret the client secret at the provider, sent with HTTP Basic, or null for a public client
  /// @param scope        the scope requested, e.g. `openid email profile`
  /// @param redirectUri  the redirect URI registered at the provider, to which the user returns with a code
  public record Config(URI issuer, String clientId, String clientSecret, String scope, URI redirectUri) {}

  private record Provider(URI authorizationEndpoint, URI tokenEndpoint, DefaultJWTProcessor<SecurityContext> idTokenProcessor) {}

  public OpenIdConnectClient(Config config) {
    this.config = config;
    this.httpClient = HttpClient.newBuilder().connectTimeout(RemoteKeys.timeout).build();
    // Discovered once it succeeds, and tried again the next time if it fails
    this.provider = Mono.fromCallable(this::discoverProvider)
        .subscribeOn(Schedulers.boundedElastic())
        .cacheInvalidateIf(_ -> false);
  }

  /// Discovers the endpoints of the provider, which is otherwise done when first needed. Fails if the provider can't
  /// be reached or its discovery document is invalid.
  public Mono<Void> discover() {
    return provider.then();
  }

  /// Where to send a user to sign in
  ///
  /// @param state  the value the provider returns the user with, e.g. the id of the sign-in
  /// @param secret the secret of the sign-in, kept in the browser of the user, which the PKCE verifier and the nonce
  ///               are derived from, e.g. of [Secrets#generate()]: 43 to 128 characters of letters, digits and
  ///               `-._~`, as RFC 7636 section 4.1 requires of a verifier. Fails with IllegalArgumentException
  ///               otherwise.
  public Mono<URI> authorizationUrl(String state, String secret) {
    return checked(secret).then(provider).map(provider -> {
      var parameters = new LinkedHashMap<String, String>();
      parameters.put("response_type", "code");
      parameters.put("client_id", config.clientId());
      parameters.put("redirect_uri", config.redirectUri().toString());
      parameters.put("scope", config.scope());
      parameters.put("state", state);
      parameters.put("nonce", nonce(secret));
      parameters.put("code_challenge", Secrets.hash(verifier(secret)));
      parameters.put("code_challenge_method", "S256");
      String endpoint = provider.authorizationEndpoint().toString();
      return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + Form.encode(parameters));
    });
  }

  /// The request redeeming the code the user returned with at the token endpoint, to send it otherwise than by
  /// [#redeem(String, String)], e.g. by an at-most-once outbox, as a code can be redeemed only once. The response is
  /// verified with [#verify(String, String)], or [#verifyWithNonce(String, String)] in a later session.
  public Mono<HttpRequest<String>> tokenRequest(String code, String secret) {
    return checked(secret).then(provider).map(provider -> {
      var parameters = new LinkedHashMap<String, String>();
      parameters.put("grant_type", "authorization_code");
      parameters.put("code", code);
      parameters.put("redirect_uri", config.redirectUri().toString());
      parameters.put("code_verifier", verifier(secret));
      var headers = new LinkedHashMap<String, String>();
      headers.put("Content-Type", "application/x-www-form-urlencoded");
      headers.put("Accept", "application/json");
      if (config.clientSecret() != null) {
        // HTTP Basic, which RFC 6749 section 2.3.1 requires every provider to support
        String credentials = URLEncoder.encode(config.clientId(), UTF_8) + ":" + URLEncoder.encode(config.clientSecret(), UTF_8);
        headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)));
      } else {
        // A public client, RFC 6749 section 4.1.3
        parameters.put("client_id", config.clientId());
      }
      return HttpRequest.create(Method.POST, provider.tokenEndpoint(), headers, Form.encode(parameters));
    });
  }

  /// The claims of the ID token of a response of the token endpoint, verified with the keys of the provider, for the
  /// client, and against the nonce of the secret. Fails if the response has no ID token or it is invalid.
  public Mono<JWTClaimsSet> verify(String tokenResponse, String secret) {
    return checked(secret).then(Mono.defer(() -> verifyWithNonce(tokenResponse, nonce(secret))));
  }

  /// The claims of the ID token of a response of the token endpoint, verified with the keys of the provider, for the
  /// client, and against the nonce, which is [#nonce(String)] of the secret of the sign-in. The nonce is sent to the
  /// provider through the browser, so it can be stored, unlike the secret, e.g. to verify the response of a token
  /// request an outbox sends, which is handled in a later session. Fails if the response has no ID token or it is
  /// invalid.
  public Mono<JWTClaimsSet> verifyWithNonce(String tokenResponse, String nonce) {
    return provider.flatMap(provider -> {
      JsonNode idToken;
      try {
        idToken = DataType.objectMapper.readTree(tokenResponse).get("id_token");
      } catch (RuntimeException e) {
        return Mono.error(new IllegalStateException("The token response is not JSON"));
      }
      if (idToken == null || !idToken.isString())
        return Mono.error(new IllegalStateException("The token response has no id_token"));
      // The keys may be fetched, which blocks
      return Mono.fromCallable(() -> verified(provider.idTokenProcessor().process(idToken.asString(), null), nonce))
          .subscribeOn(Schedulers.boundedElastic());
    });
  }

  /// How long a redemption is remembered, see [#redeem(String, String)]
  private static final Duration redemptionMemory = Duration.ofMinutes(1);

  /// A redemption of a code with a secret, by the hash of the two, and when it was started
  private record Redemption(Mono<JWTClaimsSet> result, long startedAt) {}

  private final Map<String, Redemption> redemptions = new ConcurrentHashMap<>();

  /// Redeems the code the user returned with, by sending the [token request][#tokenRequest(String, String)] with the
  /// HTTP client of the JDK, and [verifies][#verify(String, String)] the response. Fails if the code is refused or the
  /// ID token is invalid.
  ///
  /// A code can be redeemed only once, so the same code and secret redeemed again within a minute get the result of
  /// the first redemption, rather than a refusal of the provider: a transition redeeming the code may be calculated
  /// again, e.g. after a concurrent change of its entity.
  public Mono<JWTClaimsSet> redeem(String code, String secret) {
    long now = System.nanoTime();
    redemptions.values().removeIf(r -> now - r.startedAt() > redemptionMemory.toNanos());
    return redemptions.computeIfAbsent(
        Secrets.hash(code + "\n" + secret),
        _ -> new Redemption(redeemed(code, secret).cache(), now)
    ).result();
  }

  private Mono<JWTClaimsSet> redeemed(String code, String secret) {
    return tokenRequest(code, secret)
        .flatMap(request -> {
          var builder = java.net.http.HttpRequest.newBuilder(request.uri()).timeout(RemoteKeys.timeout).POST(BodyPublishers.ofString(request.body()));
          request.headers().forEach(builder::header);
          return Mono.fromFuture(() -> httpClient.sendAsync(builder.build(), BodyHandlers.ofString()));
        })
        .flatMap(response -> response.statusCode() == 200
            ? verify(response.body(), secret)
            : Mono.error(new IllegalStateException("The token endpoint responded " + response.statusCode() + ": " + response.body())));
  }

  private static JWTClaimsSet verified(JWTClaimsSet claims, String nonce) throws ParseException {
    if (nonce == null || !nonce.equals(claims.getStringClaim("nonce")))
      throw new IllegalStateException("The nonce of the ID token does not match");
    return claims;
  }

  /// The PKCE verifier is the secret itself, RFC 7636 section 4.1
  private static String verifier(String secret) {
    return secret;
  }

  /// The nonce of the sign-in of the secret, which the authorization request sends and the ID token must have. It can't
  /// be derived from the code challenge, which is sent through the browser too.
  public static String nonce(String secret) {
    return Secrets.hash("nonce:" + secret);
  }

  /// A PKCE verifier, RFC 7636 section 4.1
  private static final Pattern verifierPattern = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

  private static Mono<Void> checked(String secret) {
    return secret != null && verifierPattern.matcher(secret).matches()
        ? Mono.empty()
        : Mono.error(new IllegalArgumentException("The secret of a sign-in must be 43 to 128 characters of letters, digits and -._~"));
  }

  private Provider discoverProvider() {
    String base = config.issuer().toString();
    URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/.well-known/openid-configuration");
    JsonNode metadata;
    try {
      var response = httpClient.send(
          java.net.http.HttpRequest.newBuilder(uri).timeout(RemoteKeys.timeout).header("Accept", "application/json").GET().build(),
          BodyHandlers.ofString()
      );
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
    String issuer = required(metadata, "issuer");
    if (!config.issuer().toString().equals(issuer))
      throw new IllegalStateException("The issuer of the provider at " + uri + " is " + issuer + ", not " + config.issuer());
    var idTokenProcessor = new DefaultJWTProcessor<SecurityContext>();
    var keys = new RemoteKeys(URI.create(required(metadata, "jwks_uri")), httpClient, Clock.systemUTC(), OpenIdConnectClient::notEmpty);
    idTokenProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256, JWSAlgorithm.PS256), keys));
    idTokenProcessor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
        config.clientId(),
        new JWTClaimsSet.Builder().issuer(config.issuer().toString()).build(),
        Set.of("sub", "iat", "exp", "nonce")
    ));
    return new Provider(
        URI.create(required(metadata, "authorization_endpoint")),
        URI.create(required(metadata, "token_endpoint")),
        idTokenProcessor
    );
  }

  private static JWKSet notEmpty(JWKSet keys) {
    if (keys.getKeys().isEmpty())
      throw new IllegalArgumentException("The keys are empty");
    return keys;
  }

  private static String required(JsonNode metadata, String field) {
    JsonNode value = metadata.get(field);
    if (value == null || !value.isString())
      throw new IllegalStateException("The metadata of the provider has no " + field);
    return value.asString();
  }

}
