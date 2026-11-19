package com.github.thxmasj.backend.http.inbox;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.text.ParseException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/// Verifies access tokens issued by an OAuth 2.0 authorization server such as `apps/identity-provider`: JWTs as profiled
/// by RFC 9068, of type `at+jwt` and signed with RS256 by one of the keys of the issuer.
public final class AccessTokenVerifier {

  /// The `typ` of an access token, RFC 9068 section 2.1
  private static final JOSEObjectType accessTokenType = new JOSEObjectType("at+jwt");

  private final JWKSource<SecurityContext> keys;
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
    this(new ImmutableJWKSet<>(validated(parse(jwkSet)).toPublicJWKSet()), issuer, audience, clock);
  }

  private AccessTokenVerifier(JWKSource<SecurityContext> keys, URI issuer, String audience, Clock clock) {
    this.keys = keys;
    this.issuer = issuer.toString();
    this.audience = audience;
    this.clock = clock;
  }

  /// A verifier with the keys fetched from the `jwks_uri` of the issuer, e.g.
  /// `https://idp.example.com/.well-known/jwks.json`. The keys are fetched when the verifier is created, which fails
  /// if they can't be. They are fetched again for a token signed with a key not among them, as the issuer may have
  /// added a key since, but at most every 30 seconds. Such a fetch blocks the verification of the token.
  public static AccessTokenVerifier fetchingKeys(URI jwksUri, URI issuer, String audience, Clock clock) {
    var keys = new RemoteKeys(jwksUri, HttpClient.newBuilder().connectTimeout(RemoteKeys.timeout).build(), clock);
    try {
      validated(keys.fetch());
    } catch (KeySourceException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
    return new AccessTokenVerifier(keys, issuer, audience, clock);
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
      if (keyId == null)
        return Optional.empty();
      List<JWK> found = keys.get(new JWKSelector(new JWKMatcher.Builder().keyType(KeyType.RSA).keyID(keyId).build()), null);
      if (found.size() != 1 || !(found.getFirst() instanceof RSAKey rsaKey) || !jwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey())))
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
    } catch (KeySourceException e) {
      System.err.println("Failed to verify an access token, as the keys of the issuer can't be fetched: " + e.getMessage());
      return Optional.empty();
    } catch (ParseException | JOSEException e) {
      return Optional.empty();
    }
  }

  private static List<String> scopes(String scope) {
    return scope == null ? List.of() : Arrays.stream(scope.split(" ")).filter(s -> !s.isEmpty()).toList();
  }

}
