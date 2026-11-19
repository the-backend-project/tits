package com.github.thxmasj.backend.http.inbox;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/// Verifies access tokens issued by an OAuth 2.0 authorization server such as `apps/identity-provider`: JWTs as profiled
/// by RFC 9068, of type `at+jwt` and signed with RS256 by one of the keys of the issuer.
public final class AccessTokenVerifier {

  /// The `typ` of an access token, RFC 9068 section 2.1
  private static final JOSEObjectType accessTokenType = new JOSEObjectType("at+jwt");

  private final Supplier<JWKSet> keys;
  private final Runnable onUnknownKey;
  private final String issuer;
  private final String audience;
  private final Clock clock;

  /// The claims of a verified access token
  ///
  /// @param subject  the `sub` claim
  /// @param clientId the `client_id` claim
  /// @param scopes   the scopes of the `scope` claim
  public record AccessToken(String subject, String clientId, List<String> scopes) {}

  /// @param jwkSet   the JWK set of the issuer, e.g. as served from its `jwks_uri`. Private keys are only used for
  ///                 their public parts.
  /// @param issuer   the `iss` the tokens must have
  /// @param audience the `aud` the tokens must have, or null to accept tokens of any or no audience
  public AccessTokenVerifier(String jwkSet, URI issuer, String audience, Clock clock) {
    JWKSet keys = validated(parse(jwkSet)).toPublicJWKSet();
    this(() -> keys, () -> {}, issuer, audience, clock);
  }

  private AccessTokenVerifier(Supplier<JWKSet> keys, Runnable onUnknownKey, URI issuer, String audience, Clock clock) {
    this.keys = keys;
    this.onUnknownKey = onUnknownKey;
    this.issuer = issuer.toString();
    this.audience = audience;
    this.clock = clock;
  }

  /// A verifier with the keys fetched from the `jwks_uri` of the issuer, e.g.
  /// `https://idp.example.com/.well-known/jwks.json`. The keys are fetched when the verifier is created, which fails
  /// if they can't be.
  ///
  /// The verifier follows the key rotation of the issuer. A token signed with a key not among the keys is refused,
  /// and makes the verifier fetch the keys again in the background, but at most every 30 seconds, so that tokens
  /// signed with the issuer's new key are accepted once they have been fetched. Verifying never waits for a fetch.
  /// Keys fetched again which are not valid are not used, and the keys fetched before are kept.
  public static AccessTokenVerifier fetchingKeys(URI jwksUri, URI issuer, String audience, Clock clock) {
    var keys = new RemoteKeys(jwksUri, HttpClient.newBuilder().connectTimeout(RemoteKeys.timeout).build(), clock, AccessTokenVerifier::validated);
    try {
      keys.fetch();
    } catch (KeySourceException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
    return new AccessTokenVerifier(keys::current, keys::refreshInBackground, issuer, audience, clock);
  }

  private static JWKSet parse(String jwkSet) {
    try {
      return JWKSet.parse(jwkSet);
    } catch (ParseException e) {
      throw new IllegalArgumentException("The keys are not a JWK set: " + e.getMessage());
    }
  }

  private static JWKSet validated(JWKSet keys) {
    if (keys.getKeys().isEmpty())
      throw new IllegalArgumentException("The keys are empty");
    for (JWK key : keys.getKeys()) {
      if (!(key instanceof RSAKey) || key.getKeyID() == null)
        throw new IllegalArgumentException("The keys must be RSA keys with key ids");
    }
    return keys;
  }

  /// The claims of the token if it is valid: signed by a key of the issuer, issued by the issuer, for the audience if
  /// one is required, not expired, and with a subject and a client id.
  public Optional<AccessToken> verify(String token) {
    try {
      SignedJWT jwt = SignedJWT.parse(token);
      if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !accessTokenType.equals(jwt.getHeader().getType()))
        return Optional.empty();
      String keyId = jwt.getHeader().getKeyID();
      JWK key = keyId != null ? keys.get().getKeyByKeyId(keyId) : null;
      if (key == null && keyId != null)
        onUnknownKey.run();
      if (!(key instanceof RSAKey rsaKey) || !jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey())))
        return Optional.empty();
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      if (!issuer.equals(claims.getIssuer())
          || claims.getExpirationTime() == null
          || !claims.getExpirationTime().toInstant().isAfter(clock.instant())
          || (audience != null && (claims.getAudience() == null || !claims.getAudience().contains(audience)))
          || claims.getSubject() == null
          || claims.getStringClaim("client_id") == null)
        return Optional.empty();
      String scope = claims.getStringClaim("scope");
      return Optional.of(new AccessToken(claims.getSubject(), claims.getStringClaim("client_id"), scopes(scope)));
    } catch (ParseException | JOSEException e) {
      return Optional.empty();
    }
  }

  private static List<String> scopes(String scope) {
    return scope == null ? List.of() : Arrays.stream(scope.split(" ")).filter(s -> !s.isEmpty()).toList();
  }

}
