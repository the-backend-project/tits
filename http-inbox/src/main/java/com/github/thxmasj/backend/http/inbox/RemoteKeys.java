package com.github.thxmasj.backend.http.inbox;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// The keys of an issuer, fetched from its `jwks_uri` with the HTTP client of the JDK rather than by `java.net.URL`,
/// whose http and https protocols are not enabled in the native image. They are fetched when first needed, and again
/// for a key not among them, as the issuer may have rotated its keys, but at most every 30 seconds. Fetching blocks.
final class RemoteKeys implements JWKSource<SecurityContext> {

  static final Duration timeout = Duration.ofSeconds(10);
  static final Duration minRefetchInterval = Duration.ofSeconds(30);

  private final URI jwksUri;
  private final HttpClient httpClient;
  private final Clock clock;
  private JWKSet keys;
  private Instant attemptedAt;

  RemoteKeys(URI jwksUri, HttpClient httpClient, Clock clock) {
    this.jwksUri = jwksUri;
    this.httpClient = httpClient;
    this.clock = clock;
  }

  @Override
  public synchronized List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
    if (keys != null) {
      List<JWK> selected = selector.select(keys);
      if (!selected.isEmpty() || clock.instant().isBefore(attemptedAt.plus(minRefetchInterval)))
        return selected;
    }
    fetch();
    return selector.select(keys);
  }

  /// Fetches the keys now, e.g. to fail early if they can't be
  synchronized JWKSet fetch() throws KeySourceException {
    // An issuer which can't be reached is tried again at the same pace
    attemptedAt = clock.instant();
    try {
      var response = httpClient.send(
          HttpRequest.newBuilder(jwksUri).timeout(timeout).header("Accept", "application/json").GET().build(),
          BodyHandlers.ofString()
      );
      if (response.statusCode() != 200)
        throw new KeySourceException("Failed to fetch the keys from " + jwksUri + ": status " + response.statusCode());
      keys = JWKSet.parse(response.body()).toPublicJWKSet();
      return keys;
    } catch (IOException | ParseException e) {
      throw new KeySourceException("Failed to fetch the keys from " + jwksUri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new KeySourceException("Interrupted while fetching the keys from " + jwksUri, e);
    }
  }

}
