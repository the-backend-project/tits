package com.github.thxmasj.backend.identityprovider;

import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier;
import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier.AccessToken;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/// The keys the access tokens are signed with. Access tokens are JWTs as profiled by RFC 9068, signed with RS256.
final class Keys {

  /// The `typ` of an access token, RFC 9068 section 2.1
  private static final JOSEObjectType accessTokenType = new JOSEObjectType("at+jwt");

  private final JWKSet keys;
  private final RSAKey signingKey;
  private final URI issuer;
  private final String audience;
  private final AccessTokenVerifier verifier;

  Keys(String jwkSet, URI issuer, String audience, Clock clock) {
    try {
      this.keys = JWKSet.parse(jwkSet);
    } catch (ParseException e) {
      throw new IllegalArgumentException("The signing keys are not a JWK set: " + e.getMessage());
    }
    if (keys.getKeys().isEmpty())
      throw new IllegalArgumentException("The signing keys are empty");
    for (JWK key : keys.getKeys()) {
      if (!(key instanceof RSAKey rsaKey) || !rsaKey.isPrivate() || key.getKeyID() == null)
        throw new IllegalArgumentException("The signing keys must be private RSA keys with key ids");
    }
    this.signingKey = (RSAKey) keys.getKeys().getFirst();
    this.issuer = issuer;
    this.audience = audience;
    this.verifier = new AccessTokenVerifier(keys.toPublicJWKSet().toString(), issuer, null, clock);
  }

  /// A JWK set with a new private key, for the `generate-key` command
  static String generate() {
    try {
      RSAKey key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyIDFromThumbprint(true).generate();
      return new JWKSet(key).toString(false);
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

  URI issuer() {
    return issuer;
  }

  /// The public keys, served as the JWK set of the issuer
  String publicKeys() {
    return keys.toPublicJWKSet().toString();
  }

  String sign(Tokens.IssuedToken token) {
    var claims = new JWTClaimsSet.Builder()
        .issuer(issuer.toString())
        .subject(token.subject())
        .claim("client_id", token.clientId())
        .claim("scope", token.scope())
        .jwtID(token.id())
        .issueTime(Date.from(Instant.ofEpochSecond(token.issuedAt())))
        .expirationTime(Date.from(Instant.ofEpochSecond(token.expiresAt())));
    if (audience != null)
      claims.audience(audience);
    var jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).type(accessTokenType).keyID(signingKey.getKeyID()).build(),
        claims.build()
    );
    try {
      jwt.sign(new RSASSASigner(signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException("Failed to sign access token", e);
    }
    return jwt.serialize();
  }

  /// The claims of an access token issued by this identity provider, if it is valid. The audience is not checked, as
  /// the identity provider accepts its tokens whatever their audience.
  Optional<AccessToken> verify(String token) {
    return verifier.verify(token);
  }

}
