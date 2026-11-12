package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier.AccessToken;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// Verifies the bearer tokens of requests on their way to the inbox, RFC 6750. The token of a request is replaced by
/// what it was found to hold, in the headers [#SubjectHeader], [#ClientIdHeader] and [#ScopeHeader], so the event log
/// records the claims of the tokens but never a token. Routes read the subject with [HttpInbox#bearerToken].
///
/// The token is verified before the request enters the state machine, as the state machine may calculate the routing
/// of a request again later, when the token may have expired.
///
/// A request with an invalid token is answered with 401. A request without a bearer token is passed on without the
/// headers, which requests can't set themselves, so it is refused by the routes requiring a token.
public final class BearerTokenFilter implements HttpInboxListener.Filter {

  /// The `sub` of the token
  public static final String SubjectHeader = "X-Token-Subject";
  /// The `client_id` of the token
  public static final String ClientIdHeader = "X-Token-Client-Id";
  /// The `scope` of the token, as a space separated list of scopes
  public static final String ScopeHeader = "X-Token-Scope";

  private static final List<String> trustedHeaders = List.of(SubjectHeader, ClientIdHeader, ScopeHeader);

  private final AccessTokenVerifier verifier;
  private final String realm;

  /// @param realm the `realm` of the `WWW-Authenticate` challenges, or null for none
  public BearerTokenFilter(AccessTokenVerifier verifier, String realm) {
    this.verifier = verifier;
    this.realm = realm;
  }

  @Override
  public Mono<HttpResponseMessage> filter(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(BearerTokenFilter::isTrusted);
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return next.apply(withHeaders(request, headers));
    Optional<AccessToken> token = verifier.verify(authorization.substring(7).trim());
    if (token.isEmpty())
      return Mono.just(invalidToken());
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
    headers.put(SubjectHeader, token.get().subject());
    headers.put(ClientIdHeader, token.get().clientId());
    headers.put(ScopeHeader, String.join(" ", token.get().scopes()));
    return next.apply(withHeaders(request, headers));
  }

  private static boolean isTrusted(String header) {
    return trustedHeaders.stream().anyMatch(header::equalsIgnoreCase);
  }

  private static HttpRequestMessage withHeaders(HttpRequestMessage request, Map<String, String> headers) {
    return new HttpRequestMessage(request.method(), request.uri(), headers, request.body());
  }

  /// The error response of RFC 6750 section 3.1
  private HttpResponseMessage invalidToken() {
    String challenge = "Bearer " + (realm != null ? "realm=\"" + realm + "\", " : "")
        + "error=\"invalid_token\", error_description=\"The access token is invalid or expired\"";
    return new HttpResponseMessage(401, "Unauthorized", Map.of("WWW-Authenticate", challenge), null);
  }

}
