package com.github.thxmasj.backend.http.inbox;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import com.nimbusds.jose.JWSAlgorithm;
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
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
        .cacheInvalidateIf(_ -> false)
        .onErrorResume(e -> Mono.error(new IllegalStateException("Failed to discover the provider " + config.issuer() + ": " + e.getMessage(), e)));
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
  ///               are derived from: 43 to 128 characters of base64url, e.g. 32 random bytes encoded
  public Mono<URI> authorizationUrl(String state, String secret) {
    return provider.map(provider -> {
      var parameters = new LinkedHashMap<String, String>();
      parameters.put("response_type", "code");
      parameters.put("client_id", config.clientId());
      parameters.put("redirect_uri", config.redirectUri().toString());
      parameters.put("scope", config.scope());
      parameters.put("state", state);
      parameters.put("nonce", nonce(secret));
      parameters.put("code_challenge", hash(verifier(secret)));
      parameters.put("code_challenge_method", "S256");
      String endpoint = provider.authorizationEndpoint().toString();
      return URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + form(parameters));
    });
  }

  /// The request redeeming the code the user returned with at the token endpoint, e.g. for an at-most-once outbox to
  /// send, as a code can be redeemed only once. The response is verified with [#verify(String, String)].
  public Mono<TypedHttpRequest<String>> tokenRequest(String code, String secret) {
    return provider.map(provider -> {
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
      return TypedHttpRequest.create(Method.POST, provider.tokenEndpoint(), headers, form(parameters));
    });
  }

  /// The claims of the ID token of a response of the token endpoint, verified with the keys of the provider, for the
  /// client, and against the nonce of the secret. Fails if the response has no ID token or it is invalid.
  public Mono<JWTClaimsSet> verify(String tokenResponse, String secret) {
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
      return Mono.fromCallable(() -> verified(provider.idTokenProcessor().process(idToken.asString(), null), secret))
          .subscribeOn(Schedulers.boundedElastic());
    });
  }

  /// Redeems the code the user returned with, by sending the [token request][#tokenRequest(String, String)] with the
  /// HTTP client of the JDK, and [verifies][#verify(String, String)] the response. Fails if the code is refused or the
  /// ID token is invalid.
  public Mono<JWTClaimsSet> redeem(String code, String secret) {
    return tokenRequest(code, secret)
        .flatMap(request -> {
          var builder = HttpRequest.newBuilder(request.uri()).timeout(RemoteKeys.timeout).POST(BodyPublishers.ofString(request.payload()));
          request.headers().forEach(builder::header);
          return Mono.fromFuture(() -> httpClient.sendAsync(builder.build(), BodyHandlers.ofString()));
        })
        .flatMap(response -> response.statusCode() == 200
            ? verify(response.body(), secret)
            : Mono.error(new IllegalStateException("The token endpoint responded " + response.statusCode() + ": " + response.body())));
  }

  private static JWTClaimsSet verified(JWTClaimsSet claims, String secret) throws ParseException {
    if (!nonce(secret).equals(claims.getStringClaim("nonce")))
      throw new IllegalStateException("The nonce of the ID token does not match");
    return claims;
  }

  /// The PKCE verifier is the secret itself, RFC 7636 section 4.1
  private static String verifier(String secret) {
    return secret;
  }

  /// The nonce can't be derived from the code challenge, which is sent through the browser
  private static String nonce(String secret) {
    return hash("nonce:" + secret);
  }

  private static String hash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Provider discoverProvider() {
    String base = config.issuer().toString();
    URI uri = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/.well-known/openid-configuration");
    JsonNode metadata;
    try {
      var response = httpClient.send(
          HttpRequest.newBuilder(uri).timeout(RemoteKeys.timeout).header("Accept", "application/json").GET().build(),
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
    var keys = new RemoteKeys(URI.create(required(metadata, "jwks_uri")), httpClient, Clock.systemUTC());
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

  private static String required(JsonNode metadata, String field) {
    JsonNode value = metadata.get(field);
    if (value == null || !value.isString())
      throw new IllegalStateException("The metadata of the provider has no " + field);
    return value.asString();
  }

  private static String form(Map<String, String> parameters) {
    return parameters.entrySet().stream()
        .map(e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
        .collect(Collectors.joining("&"));
  }

}
