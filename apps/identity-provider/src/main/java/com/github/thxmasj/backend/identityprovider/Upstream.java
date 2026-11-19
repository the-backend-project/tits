package com.github.thxmasj.backend.identityprovider;

import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.identityprovider.Authorizations.User;
import com.nimbusds.jwt.JWTClaimsSet;
import java.net.URI;
import java.text.ParseException;
import reactor.core.publisher.Mono;

/// The upstream OpenID Connect provider users sign in with, as a confidential client of it with the authorization
/// code flow. Used by the [Edge] only: the code of the upstream provider, the client secret and the upstream ID token
/// never enter the event log, only the claims of the user.
///
/// The endpoints are found by OpenID Connect Discovery when the identity provider starts, which fails if they can't
/// be. The user is bound to the browser which started the sign-in by PKCE and the nonce, both derived from a secret
/// the edge keeps in a cookie.
final class Upstream {

  private final OpenIdConnectClient client;

  /// @param issuer the issuer of the identity provider, whose `/callback` is the redirect URI at the upstream provider
  Upstream(Config config, URI issuer) {
    this.client = new OpenIdConnectClient(new OpenIdConnectClient.Config(
        config.issuer(),
        config.clientId(),
        config.clientSecret(),
        config.scope(),
        URI.create(issuer + "/callback")
    ));
    client.discover().block();
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
  Mono<URI> authorizationUrl(String state, String secret) {
    return client.authorizationUrl(state, secret);
  }

  /// The user signed in, by redeeming the code of the upstream provider and verifying the ID token it gives. Fails if
  /// the code is refused or the ID token is invalid.
  Mono<User> signIn(String code, String secret) {
    return client.redeem(code, secret).handle((claims, sink) -> {
      try {
        sink.next(user(claims));
      } catch (ParseException e) {
        sink.error(new IllegalStateException("The upstream ID token has an invalid claim: " + e.getMessage(), e));
      }
    });
  }

  private static User user(JWTClaimsSet claims) throws ParseException {
    Object authTime = claims.getClaim("auth_time");
    return new User(
        claims.getSubject(),
        claims.getStringClaim("email"),
        claims.getBooleanClaim("email_verified"),
        claims.getStringClaim("name"),
        authTime instanceof Number number ? number.longValue() : null
    );
  }

}
