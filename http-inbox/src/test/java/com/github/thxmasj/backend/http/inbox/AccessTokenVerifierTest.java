package com.github.thxmasj.backend.http.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier.AccessToken;
import com.github.thxmasj.backend.http.inbox.Authorization.Authorized;
import com.github.thxmasj.backend.http.inbox.Authorization.Refused;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

public class AccessTokenVerifierTest {

  private static final URI issuer = URI.create("https://idp.example.com");
  private static final Instant now = Instant.parse("2026-11-12T12:00:00Z");
  private static final RSAKey key = generate("key-1");
  private static final RSAKey otherKey = generate("key-2");

  private final AccessTokenVerifier verifier = verifier(null);

  @Test
  public void whenTokenIsValidThenClaimsAreReturned() {
    assertThat(verifier.verify(token(key, c -> c)))
        .contains(new AccessToken("https://wallet.example.com", "wallet", List.of("payments", "refunds")));
  }

  @Test
  public void whenVerifierHasPrivateKeysThenTokensAreVerifiedWithTheirPublicParts() {
    var verifier = new AccessTokenVerifier(new JWKSet(key).toString(false), issuer, null, clock());
    assertThat(verifier.verify(token(key, c -> c))).isPresent();
  }

  @Test
  public void whenSignedByAnotherKeyWithTheSameKeyIdThenRejected() throws JOSEException {
    var impostor = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
    assertThat(verifier.verify(token(impostor, c -> c))).isEmpty();
  }

  @Test
  public void whenKeyIdIsUnknownThenRejected() {
    assertThat(verifier.verify(token(otherKey, c -> c))).isEmpty();
  }

  @Test
  public void whenExpiredThenRejected() {
    assertThat(verifier.verify(token(key, c -> c.expirationTime(Date.from(now))))).isEmpty();
    assertThat(verifier.verify(token(key, c -> c.expirationTime(null)))).isEmpty();
  }

  @Test
  public void whenIssuerDiffersThenRejected() {
    assertThat(verifier.verify(token(key, c -> c.issuer("https://evil.example.com")))).isEmpty();
  }

  @Test
  public void whenSubjectOrClientIdIsMissingThenRejected() {
    assertThat(verifier.verify(token(key, c -> c.subject(null)))).isEmpty();
    assertThat(verifier.verify(token(key, c -> c.claim("client_id", null)))).isEmpty();
  }

  @Test
  public void whenAudienceIsRequiredThenOnlyTokensForItAreAccepted() {
    var verifier = verifier("payments-api");
    assertThat(verifier.verify(token(key, c -> c.audience("payments-api")))).isPresent();
    assertThat(verifier.verify(token(key, c -> c.audience(List.of("other", "payments-api"))))).isPresent();
    assertThat(verifier.verify(token(key, c -> c.audience("other")))).isEmpty();
    assertThat(verifier.verify(token(key, c -> c))).isEmpty();
  }

  @Test
  public void whenNotAnAccessTokenThenRejected() throws JOSEException {
    var plainJwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims().build());
    plainJwt.sign(new RSASSASigner(key));
    assertThat(verifier.verify(plainJwt.serialize())).isEmpty();
    assertThat(verifier.verify("not a token")).isEmpty();
  }

  @Test
  public void whenSignedWithHmacThenRejected() throws JOSEException {
    var jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).keyID(key.getKeyID()).build(),
        claims().build()
    );
    jwt.sign(new MACSigner(new byte[32]));
    assertThat(verifier.verify(jwt.serialize())).isEmpty();
  }

  @Test
  public void whenKeysAreNotRsaKeysWithIdsThenRefused() {
    assertThatThrownBy(() -> new AccessTokenVerifier("{\"keys\":[]}", issuer, null, clock()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerifier(new JWKSet(generate(null)).toString(), issuer, null, clock()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessTokenVerifier("nonsense", issuer, null, clock()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void whenAuthorizingValidTokenThenSubjectIsAuthorized() {
    assertThat(authorize(Map.of("Authorization", "Bearer " + token(key, c -> c)), "payments", "refunds"))
        .isEqualTo(new Authorized("https://wallet.example.com"));
    assertThat(authorize(Map.of("authorization", "bearer " + token(key, c -> c))))
        .isEqualTo(new Authorized("https://wallet.example.com"));
  }

  @Test
  public void whenAuthorizingTokenWithoutRequiredScopeThenForbiddenWithInsufficientScope() {
    assertThat(authorize(Map.of("Authorization", "Bearer " + token(key, c -> c)), "payments", "admin"))
        .isEqualTo(new Refused(
            403,
            "Bearer error=\"insufficient_scope\", error_description=\"Bearer token lacks the scope admin\", scope=\"payments admin\"",
            "Bearer token lacks the scope admin"
        ));
  }

  @Test
  public void whenAuthorizingInvalidTokenThenUnauthorizedWithInvalidToken() {
    var refused = new Refused(
        401,
        "Bearer error=\"invalid_token\", error_description=\"Bearer token is invalid or expired\"",
        "Bearer token is invalid or expired"
    );
    assertThat(authorize(Map.of("Authorization", "Bearer " + token(otherKey, c -> c)))).isEqualTo(refused);
    assertThat(authorize(Map.of("Authorization", "Bearer " + token(key, c -> c.expirationTime(Date.from(now)))))).isEqualTo(refused);
  }

  @Test
  public void whenAuthorizingWithoutBearerTokenThenUnauthorizedWithoutError() {
    assertThat(authorize(Map.of())).isEqualTo(new Refused(401, "Bearer", "Authorization header missing"));
    assertThat(authorize(Map.of("Authorization", "Basic d2FsbGV0OnNlY3JldA==")))
        .isEqualTo(new Refused(401, "Bearer", "Authorization token is not of type Bearer"));
  }

  private Authorization authorize(Map<String, String> headers, String... requiredScopes) {
    return HttpInbox.<Void>bearerToken(verifier, requiredScopes)
        .apply(new HttpRequestMessage(Method.POST, URI.create("/payments"), headers, null), null);
  }

  private static AccessTokenVerifier verifier(String audience) {
    return new AccessTokenVerifier(new JWKSet(key).toPublicJWKSet().toString(), issuer, audience, clock());
  }

  private static Clock clock() {
    return Clock.fixed(now, ZoneOffset.UTC);
  }

  private static JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer(issuer.toString())
        .subject("https://wallet.example.com")
        .claim("client_id", "wallet")
        .claim("scope", "payments refunds")
        .jwtID("4f8c2a6e-9d13-4b70-8e5f-3a1d7c9b2e48")
        .issueTime(Date.from(now.minusSeconds(60)))
        .expirationTime(Date.from(now.plusSeconds(60)));
  }

  private static String token(RSAKey signingKey, UnaryOperator<JWTClaimsSet.Builder> customizer) {
    var jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(signingKey.getKeyID()).build(),
        customizer.apply(claims()).build()
    );
    try {
      jwt.sign(new RSASSASigner(signingKey));
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
    return jwt.serialize();
  }

  private static RSAKey generate(String keyId) {
    try {
      return new RSAKeyGenerator(2048).keyID(keyId).generate();
    } catch (JOSEException e) {
      throw new IllegalStateException(e);
    }
  }

}
