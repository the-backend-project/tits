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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/// The keys of an issuer, fetched from its `jwks_uri` with the HTTP client of the JDK rather than by `java.net.URL`,
/// whose http and https protocols are not enabled in the native image.
///
/// The keys are fetched again for a key not among them, as the issuer may have rotated its keys, but at most every 30
/// seconds after the last attempt, whether it succeeded or not, so tokens of random key ids can't make it fetch more
/// often. Keys fetched again which are not valid are not used, and the keys fetched before are kept.
final class RemoteKeys implements JWKSource<SecurityContext> {

  static final Duration timeout = Duration.ofSeconds(10);
  static final Duration minRefetchInterval = Duration.ofSeconds(30);

  private final URI jwksUri;
  private final HttpClient httpClient;
  private final Clock clock;
  private final UnaryOperator<JWKSet> validator;
  private final Object fetchLock = new Object();
  private final AtomicBoolean fetchingInBackground = new AtomicBoolean();
  // Read without a lock, so verifying with a known key never waits for a fetch
  private volatile JWKSet keys;
  private volatile Instant attemptedAt;

  /// @param validator returns the keys if they may be used, and throws IllegalArgumentException otherwise
  RemoteKeys(URI jwksUri, HttpClient httpClient, Clock clock, UnaryOperator<JWKSet> validator) {
    this.jwksUri = jwksUri;
    this.httpClient = httpClient;
    this.clock = clock;
    this.validator = validator;
  }

  /// The keys fetched last, or empty if none have been
  JWKSet current() {
    JWKSet current = keys;
    return current != null ? current : new JWKSet();
  }

  /// The keys, fetched again if none match and it is time to, which blocks. For a caller which may block, such as
  /// the verification of an ID token on a worker thread.
  @Override
  public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
    List<JWK> selected = selector.select(current());
    if (!selected.isEmpty() || !due())
      return selected;
    synchronized (fetchLock) {
      // Another thread may have fetched them while this one waited
      selected = selector.select(current());
      if (!selected.isEmpty() || !due())
        return selected;
      fetch();
    }
    return selector.select(current());
  }

  /// Fetches the keys again in the background if it is time to, for a caller which must not block, such as the
  /// verification of an access token on the event loop
  void refreshInBackground() {
    if (!due() || !fetchingInBackground.compareAndSet(false, true))
      return;
    Thread.ofVirtual().name("jwks-fetch").start(() -> {
      try {
        synchronized (fetchLock) {
          if (due())
            fetch();
        }
      } catch (KeySourceException e) {
        System.err.println(e.getMessage());
      } finally {
        fetchingInBackground.set(false);
      }
    });
  }

  /// Fetches the keys now, e.g. to fail early if they can't be. Fails if they can't be fetched or are not valid, and
  /// the keys fetched before are kept.
  JWKSet fetch() throws KeySourceException {
    synchronized (fetchLock) {
      attemptedAt = clock.instant();
      JWKSet fetched;
      try {
        var response = httpClient.send(
            HttpRequest.newBuilder(jwksUri).timeout(timeout).header("Accept", "application/json").GET().build(),
            BodyHandlers.ofString()
        );
        if (response.statusCode() != 200)
          throw new KeySourceException("Failed to fetch the keys from " + jwksUri + ": status " + response.statusCode());
        fetched = validator.apply(JWKSet.parse(response.body()).toPublicJWKSet());
      } catch (IOException | ParseException | IllegalArgumentException e) {
        throw new KeySourceException("Failed to fetch the keys from " + jwksUri + ": " + (e.getMessage() != null ? e.getMessage() : e), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new KeySourceException("Interrupted while fetching the keys from " + jwksUri, e);
      }
      keys = fetched;
      return fetched;
    }
  }

  private boolean due() {
    Instant attempted = attemptedAt;
    return attempted == null || !clock.instant().isBefore(attempted.plus(minRefetchInterval));
  }

}
