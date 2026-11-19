package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.identityprovider.Inbox.ClientIdHeader;
import static com.github.thxmasj.backend.identityprovider.Inbox.RegisteredByHeader;
import static com.github.thxmasj.backend.identityprovider.Inbox.SecretHashHeader;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier.AccessToken;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

/// The edge between the HTTP clients and the state machine. Credentials are handled here and nowhere else: client
/// secrets are hashed before a request enters the event log, generated secrets are added to responses after they leave
/// it, tokens are signed on their way out and verified on their way in, and the secrets of imported clients are
/// verified against their BCrypt hashes. Likewise, authorization codes are generated here and enter the event log only
/// as hashes, and users are signed in with the upstream provider here, so its codes and tokens never enter it. The
/// event log only records hashes, the claims of the tokens and the claims of the users signed in.
///
/// The edge also serves what does not change with the requests: the health check, the public keys and the metadata.
final class Edge implements HttpInboxListener.Filter {

  /// The cookie holding the secret of a sign-in with the upstream provider, by the authorization it is for
  private static final String cookiePrefix = "idp_authorization_";
  /// How long a user has to sign in with the upstream provider
  private static final Duration signInLifetime = Duration.ofMinutes(10);
  /// The errors of an upstream provider passed on to the client, RFC 6749 section 4.1.2.1 and OpenID Connect Core
  /// section 3.1.2.6. Others are passed on as `server_error`.
  private static final Set<String> upstreamErrors = Set.of(
      "access_denied", "temporarily_unavailable", "interaction_required", "login_required", "account_selection_required", "consent_required"
  );

  private final Keys keys;
  private final Upstream upstream;
  private final String metadata;
  /// The BCrypt hashes of the secrets of the imported clients, by client id
  private final Map<String, String> importedSecretHashes;

  /// @param upstream the provider users sign in with, or null if users can't sign in
  Edge(Keys keys, Upstream upstream, Map<String, String> importedSecretHashes) {
    this.keys = keys;
    this.upstream = upstream;
    this.metadata = metadata(keys, upstream != null);
    this.importedSecretHashes = Map.copyOf(importedSecretHashes);
  }

  @Override
  public Mono<HttpResponseMessage> filter(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    // The cookies hold the secrets of sign-ins, so they are kept out of the event log
    String cookies = request.headerValue("Cookie");
    request = withoutTrustedHeaders(request);
    if (upstream != null) {
      switch (request.uri().getPath()) {
        case "/.well-known/openid-configuration" -> {
          return get(request, () -> response(200, "OK", "application/json", metadata));
        }
        case "/authorize" -> {
          return get(request, next, this::authorize);
        }
        case "/callback" -> {
          return get(request, next, (r, n) -> callback(r, cookies, n));
        }
        case "/userinfo" -> {
          return get(request, next, this::userInfo);
        }
        default -> {}
      }
    }
    return switch (request.uri().getPath()) {
      case "/health" -> get(request, () -> response(200, "OK", "text/plain", "OK"));
      case "/.well-known/jwks.json" -> get(request, () -> response(200, "OK", "application/jwk-set+json", keys.publicKeys()));
      case "/.well-known/oauth-authorization-server" -> get(request, () -> response(200, "OK", "application/json", metadata));
      case "/token" -> post(request, next, this::token);
      case "/clients" -> post(request, next, this::register);
      default -> Mono.just(response(404, "Not Found", null, null));
    };
  }

  /// The authorization endpoint of RFC 6749 section 3.1. The state machine checks the request, and the user is
  /// redirected to the upstream provider to sign in, or back to the client with an error. The secret of the sign-in
  /// with the upstream provider is kept in a cookie, which binds it to the browser the user started it in.
  private Mono<HttpResponseMessage> authorize(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    return next.apply(request).flatMap(response -> switch (response.statusCode()) {
      case 200 -> {
        String authorization = DataType.objectMapper.readTree(response.body()).get("authorization").asString();
        String secret = Secrets.generate();
        yield upstream.authorizationUrl(authorization, secret).map(url -> {
          var headers = noStore(Map.of());
          headers.remove("Content-Type");
          headers.put("Location", url.toString());
          headers.put("Set-Cookie", cookie(authorization, secret, signInLifetime));
          return new HttpResponseMessage(302, "Found", headers, null);
        });
      }
      case 302 -> Mono.just(redirect(response, null));
      default -> Mono.just(errorPage(response));
    });
  }

  /// The redirect URI of the identity provider at the upstream provider. The code of the upstream provider is redeemed
  /// here, and the user it signed in passed on to the state machine with the hash of the code generated for the
  /// client. The client gets the code by the redirect back to it.
  private Mono<HttpResponseMessage> callback(HttpRequestMessage request, String cookies, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    Map<String, String> query;
    try {
      String raw = request.uri().getRawQuery();
      query = Form.parse(raw != null ? raw.getBytes(UTF_8) : null);
    } catch (IllegalArgumentException e) {
      return Mono.just(errorPage("The response of the identity provider is invalid"));
    }
    String authorization = query.get("state");
    try {
      UUID.fromString(authorization != null ? authorization : "");
    } catch (IllegalArgumentException e) {
      return Mono.just(errorPage("The response of the identity provider is invalid"));
    }
    String secret = cookie(cookies, cookiePrefix + authorization);
    if (secret == null)
      return Mono.just(errorPage("The sign-in has expired, or was started in another browser. Please sign in again."));
    String clearCookie = cookie(authorization, "", Duration.ZERO);
    Mono<HttpRequestMessage> passed;
    String code;
    if (query.containsKey("error") || !query.containsKey("code")) {
      // The error of the upstream provider is passed on only if it is one of the known ones, which are short
      String error = upstreamErrors.contains(query.get("error")) ? query.get("error") : "server_error";
      code = null;
      passed = Mono.just(signInFailed(authorization, new Authorizations.Failure(error, "The sign-in failed at the identity provider: " + error)));
    } else {
      code = Secrets.generate();
      passed = upstream.signIn(query.get("code"), secret)
          .map(user -> signedIn(authorization, user, Secrets.hash(code)))
          .onErrorResume(e -> {
            System.err.println("Sign-in with the upstream provider failed for authorization " + authorization + ": " + e);
            return Mono.just(signInFailed(authorization, new Authorizations.Failure("server_error", "The sign-in with the identity provider failed")));
          });
    }
    return passed.flatMap(next)
        .map(response -> response.statusCode() == 302 ? redirect(response, code) : errorPage(response))
        .map(response -> withHeader(response, "Set-Cookie", clearCookie));
  }

  private static HttpRequestMessage signedIn(String authorization, Authorizations.User user, String codeHash) {
    var headers = Map.of("Content-Type", "application/json", Inbox.AuthorizationIdHeader, authorization, Inbox.CodeHashHeader, codeHash);
    return new HttpRequestMessage(Method.POST, URI.create("/callback"), headers, DataType.objectMapper.writeValueAsBytes(user));
  }

  private static HttpRequestMessage signInFailed(String authorization, Authorizations.Failure failure) {
    var headers = Map.of("Content-Type", "application/json", Inbox.AuthorizationIdHeader, authorization);
    return new HttpRequestMessage(Method.POST, URI.create("/callback"), headers, DataType.objectMapper.writeValueAsBytes(failure));
  }

  /// The UserInfo endpoint of OpenID Connect Core section 5.3, called with an access token of a user
  private Mono<HttpResponseMessage> userInfo(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return Mono.just(bearerError(401, null, null, null));
    String token = authorization.substring(7).trim();
    Optional<AccessToken> principal = keys.verify(token);
    Optional<String> tokenId = principal.flatMap(_ -> Keys.tokenId(token));
    if (tokenId.isEmpty())
      return Mono.just(bearerError(401, "invalid_token", "The access token is invalid or expired", null));
    if (!principal.get().scopes().contains("openid"))
      return Mono.just(bearerError(403, "insufficient_scope", "The access token lacks the scope openid", "openid"));
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
    headers.put(Inbox.TokenIdHeader, tokenId.get());
    return next.apply(new HttpRequestMessage(request.method(), request.uri(), headers, null))
        .map(response -> response.statusCode() == 200 ?
            new HttpResponseMessage(200, "OK", noStore(response.headers()), response.body()) :
            // The token is not one of a user, e.g. of the client credentials grant
            bearerError(401, "invalid_token", "The access token is not one of a user", null));
  }

  /// The token endpoint of RFC 6749 section 3.2, for the client credentials grant and the authorization code grant,
  /// with the client authenticated by HTTP Basic, which RFC 6749 section 2.3.1 requires every server to support.
  private Mono<HttpResponseMessage> token(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String contentType = request.headerValue("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded"))
      return Mono.just(tokenError(400, "invalid_request", "Content-Type must be application/x-www-form-urlencoded"));
    Map<String, String> form;
    try {
      form = Form.parse(request.body());
    } catch (IllegalArgumentException e) {
      return Mono.just(tokenError(400, "invalid_request", e.getMessage()));
    }
    // A secret in the body would be stored in the event log, as only the Authorization header is replaced
    if (form.containsKey("client_secret") || form.containsKey("client_assertion"))
      return Mono.just(tokenError(400, "invalid_request", "Clients must authenticate with HTTP Basic"));
    Optional<String[]> credentials = basicCredentials(request.headerValue("Authorization"));
    if (credentials.isEmpty())
      return Mono.just(tokenError(401, "invalid_client", "Clients must authenticate with HTTP Basic"));
    String clientId = credentials.get()[0];
    String secret = credentials.get()[1];
    if (form.containsKey("client_id") && !form.get("client_id").equals(clientId))
      return Mono.just(tokenError(400, "invalid_request", "client_id does not match the authenticated client"));
    String grantType = form.get("grant_type");
    if (grantType == null)
      return Mono.just(tokenError(400, "invalid_request", "grant_type is missing"));
    if (!grantType.equals(Clients.ClientCredentials) && !grantType.equals(Clients.AuthorizationCode))
      return Mono.just(tokenError(400, "unsupported_grant_type", "Only the client_credentials and authorization_code grants are supported"));
    if (grantType.equals(Clients.ClientCredentials) && form.containsKey("scope") && !Scopes.isValid(form.get("scope")))
      return Mono.just(tokenError(400, "invalid_scope", "scope must be a space separated list of scopes"));
    var headers = new HashMap<>(request.headers());
    byte[] body = request.body();
    if (grantType.equals(Clients.AuthorizationCode)) {
      String code = form.get("code");
      String verifier = form.get("code_verifier");
      if (code == null || code.isEmpty())
        return Mono.just(tokenError(400, "invalid_request", "code is missing"));
      if (form.get("redirect_uri") == null)
        return Mono.just(tokenError(400, "invalid_request", "redirect_uri is missing"));
      if (verifier != null && !Inbox.codeChallengePattern.matcher(verifier).matches())
        return Mono.just(tokenError(400, "invalid_request", "code_verifier is invalid"));
      // The code and the code verifier are replaced by their hashes, which is the S256 challenge of the verifier
      headers.put(Inbox.CodeHashHeader, Secrets.hash(code));
      if (verifier != null)
        headers.put(Inbox.CodeChallengeHeader, Secrets.hash(verifier));
      var rest = new LinkedHashMap<>(form);
      rest.remove("code");
      rest.remove("code_verifier");
      body = Form.encode(rest).getBytes(UTF_8);
    }
    if (!Clients.isValidClientId(clientId))
      return Mono.just(tokenError(401, "invalid_client", "Client authentication failed"));
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Content-Length"));
    headers.put(ClientIdHeader, clientId);
    headers.put(SecretHashHeader, secretHash(clientId, secret));
    return next.apply(new HttpRequestMessage(request.method(), request.uri(), headers, body))
        .map(response -> switch (response.statusCode()) {
          case 200 -> grantType.equals(Clients.AuthorizationCode) ? userTokensResponse(response) : tokenResponse(response);
          case 401 -> withHeader(response, "WWW-Authenticate", basicChallenge());
          default -> response;
        });
  }

  /// The hash the state machine compares with that of the client. The secret of an imported client is verified here
  /// against its BCrypt hash, see [ImportedClients].
  private String secretHash(String clientId, String secret) {
    String bcryptHash = importedSecretHashes.get(clientId);
    return bcryptHash != null ? ImportedClients.secretHash(secret, bcryptHash) : Secrets.hash(secret);
  }

  /// The access token response of RFC 6749 section 5.1, with the token signed from the claims the state machine
  /// issued
  private HttpResponseMessage tokenResponse(HttpResponseMessage issued) {
    var token = DataType.objectMapper.readValue(issued.body(), Tokens.IssuedToken.class);
    var body = new LinkedHashMap<String, Object>();
    body.put("access_token", keys.sign(token));
    body.put("token_type", "Bearer");
    body.put("expires_in", token.expiresAt() - token.issuedAt());
    body.put("scope", token.scope());
    return new HttpResponseMessage(200, "OK", noStore(issued.headers()), DataType.objectMapper.writeValueAsBytes(body));
  }

  /// The access token response of RFC 6749 section 5.1 with the ID token of OpenID Connect Core section 3.1.3.3
  private HttpResponseMessage userTokensResponse(HttpResponseMessage issued) {
    var tokens = DataType.objectMapper.readValue(issued.body(), Tokens.UserTokens.class);
    var body = new LinkedHashMap<String, Object>();
    body.put("access_token", keys.sign(tokens.accessToken()));
    body.put("token_type", "Bearer");
    body.put("expires_in", tokens.accessToken().expiresAt() - tokens.accessToken().issuedAt());
    body.put("scope", tokens.accessToken().scope());
    body.put("id_token", keys.sign(tokens.idToken()));
    return new HttpResponseMessage(200, "OK", noStore(issued.headers()), DataType.objectMapper.writeValueAsBytes(body));
  }

  /// Registers a client on behalf of a client with the registration scope. The secret of the new client is generated
  /// here, so only its hash enters the event log.
  private Mono<HttpResponseMessage> register(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return Mono.just(bearerError(401, null, null, null));
    Optional<AccessToken> principal = keys.verify(authorization.substring(7).trim());
    if (principal.isEmpty())
      return Mono.just(bearerError(401, "invalid_token", "The access token is invalid or expired", null));
    if (!principal.get().scopes().contains(Clients.RegistrationScope))
      return Mono.just(bearerError(403, "insufficient_scope", "The access token lacks the scope " + Clients.RegistrationScope, Clients.RegistrationScope));
    String secret = Secrets.generate();
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
    return next.apply(registration(headers, principal.get().clientId(), Secrets.hash(secret), request))
        .map(response -> response.statusCode() == 201 ? withSecret(response, secret) : response);
  }

  /// A registration request as it enters the state machine
  ///
  /// @param registeredBy the client id of the client registering the client
  static HttpRequestMessage registration(Map<String, String> headers, String registeredBy, String secretHash, HttpRequestMessage request) {
    var trusted = new HashMap<>(headers);
    trusted.put(RegisteredByHeader, registeredBy);
    trusted.put(SecretHashHeader, secretHash);
    return new HttpRequestMessage(request.method(), request.uri(), trusted, request.body());
  }

  private static HttpResponseMessage withSecret(HttpResponseMessage registered, String secret) {
    var body = (ObjectNode) DataType.objectMapper.readTree(registered.body());
    body.put("client_secret", secret);
    // The secret does not expire, RFC 7591 section 3.2.1
    body.put("client_secret_expires_at", 0);
    return new HttpResponseMessage(201, "Created", noStore(registered.headers()), DataType.objectMapper.writeValueAsBytes(body));
  }

  /// The client id and secret of a Basic Authorization header, which are form-urlencoded, RFC 6749 section 2.3.1
  private static Optional<String[]> basicCredentials(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6))
      return Optional.empty();
    try {
      String decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), UTF_8);
      int colon = decoded.indexOf(':');
      if (colon < 0)
        return Optional.empty();
      String clientId = Form.decode(decoded.substring(0, colon));
      String secret = Form.decode(decoded.substring(colon + 1));
      if (clientId.isEmpty() || secret.isEmpty())
        return Optional.empty();
      return Optional.of(new String[] {clientId, secret});
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /// Requests can't set the headers the edge tells the state machine what it has authenticated with, and their
  /// cookies are not passed on
  private static HttpRequestMessage withoutTrustedHeaders(HttpRequestMessage request) {
    if (request.headers().keySet().stream().noneMatch(Edge::isTrusted))
      return request;
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(Edge::isTrusted);
    return new HttpRequestMessage(request.method(), request.uri(), headers, request.body());
  }

  private static boolean isTrusted(String header) {
    return header.equalsIgnoreCase("Cookie") || Inbox.trustedHeaders.stream().anyMatch(header::equalsIgnoreCase);
  }

  private static Mono<HttpResponseMessage> get(HttpRequestMessage request, Supplier<HttpResponseMessage> response) {
    return Mono.just(request.method() == Method.GET ? response.get() : methodNotAllowed("GET"));
  }

  private static Mono<HttpResponseMessage> get(
      HttpRequestMessage request,
      Function<HttpRequestMessage, Mono<HttpResponseMessage>> next,
      BiFunction<HttpRequestMessage, Function<HttpRequestMessage, Mono<HttpResponseMessage>>, Mono<HttpResponseMessage>> handler
  ) {
    return request.method() == Method.GET ? handler.apply(request, next) : Mono.just(methodNotAllowed("GET"));
  }

  /// The redirect of the user to the client, which the state machine has made, with the code if the user signed in
  private static HttpResponseMessage redirect(HttpResponseMessage response, String code) {
    var redirect = DataType.objectMapper.readValue(response.body(), Inbox.Redirect.class);
    var parameters = new LinkedHashMap<String, String>();
    if (code != null && !redirect.parameters().containsKey("error"))
      parameters.put("code", code);
    parameters.putAll(redirect.parameters());
    String uri = redirect.redirectUri();
    var headers = noStore(Map.of());
    headers.remove("Content-Type");
    headers.put("Location", uri + (uri.contains("?") ? "&" : "?") + Form.encode(parameters));
    return new HttpResponseMessage(302, "Found", headers, null);
  }

  /// A page for the user, when the client can't be redirected to, from the error response of the state machine
  private static HttpResponseMessage errorPage(HttpResponseMessage response) {
    String description = null;
    try {
      var body = DataType.objectMapper.readTree(response.body());
      if (body != null && body.get("error_description") != null && body.get("error_description").isString())
        description = body.get("error_description").asString();
    } catch (RuntimeException e) {
      // Not an error response of the identity provider, e.g. of an unknown or used authorization
    }
    return errorPage(description != null ? description : "The sign-in request is invalid or has already been used");
  }

  private static HttpResponseMessage errorPage(String description) {
    var headers = noStore(Map.of());
    headers.put("Content-Type", "text/plain;charset=UTF-8");
    return new HttpResponseMessage(400, "Bad Request", headers, ("Sign-in failed: " + description + "\n").getBytes(UTF_8));
  }

  /// The cookie with the secret of a sign-in, only sent to the callback. It is sent with the redirect from the
  /// upstream provider, which is a top-level navigation, with SameSite=Lax.
  private String cookie(String authorization, String secret, Duration maxAge) {
    return cookiePrefix + authorization + "=" + secret
        + "; Path=/callback; Max-Age=" + maxAge.toSeconds() + "; HttpOnly; SameSite=Lax"
        + ("https".equals(keys.issuer().getScheme()) ? "; Secure" : "");
  }

  /// The value of a cookie of a Cookie header, or null
  private static String cookie(String cookies, String name) {
    if (cookies == null)
      return null;
    for (String cookie : cookies.split(";")) {
      int i = cookie.indexOf('=');
      if (i > 0 && cookie.substring(0, i).trim().equals(name)) {
        String value = cookie.substring(i + 1).trim();
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private static Mono<HttpResponseMessage> post(
      HttpRequestMessage request,
      Function<HttpRequestMessage, Mono<HttpResponseMessage>> next,
      BiFunction<HttpRequestMessage, Function<HttpRequestMessage, Mono<HttpResponseMessage>>, Mono<HttpResponseMessage>> handler
  ) {
    return request.method() == Method.POST ? handler.apply(request, next) : Mono.just(methodNotAllowed("POST"));
  }

  private static HttpResponseMessage methodNotAllowed(String allowed) {
    return new HttpResponseMessage(405, "Method Not Allowed", Map.of("Allow", allowed), null);
  }

  private HttpResponseMessage tokenError(int status, String error, String description) {
    var body = new LinkedHashMap<String, String>();
    body.put("error", error);
    body.put("error_description", description);
    var headers = noStore(Map.of());
    if (status == 401)
      headers.put("WWW-Authenticate", basicChallenge());
    return new HttpResponseMessage(status, status == 401 ? "Unauthorized" : "Bad Request", headers, DataType.objectMapper.writeValueAsBytes(body));
  }

  private String basicChallenge() {
    return "Basic realm=\"" + keys.issuer() + "\", charset=\"UTF-8\"";
  }

  /// The error response of RFC 6750 section 3
  ///
  /// @param scope the scope required, for insufficient_scope
  private HttpResponseMessage bearerError(int status, String error, String description, String scope) {
    var challenge = new StringBuilder("Bearer realm=\"" + keys.issuer() + "\"");
    if (error != null)
      challenge.append(", error=\"").append(error).append("\", error_description=\"").append(description).append("\"");
    if (scope != null)
      challenge.append(", scope=\"").append(scope).append("\"");
    return new HttpResponseMessage(status, status == 401 ? "Unauthorized" : "Forbidden", Map.of("WWW-Authenticate", challenge.toString()), null);
  }

  private static Map<String, String> noStore(Map<String, String> headers) {
    var result = new HashMap<>(headers);
    result.put("Content-Type", "application/json;charset=UTF-8");
    result.put("Cache-Control", "no-store");
    result.put("Pragma", "no-cache");
    return result;
  }

  private static HttpResponseMessage withHeader(HttpResponseMessage response, String name, String value) {
    var headers = new HashMap<>(response.headers());
    headers.put(name, value);
    return new HttpResponseMessage(response.statusCode(), response.reasonPhrase(), headers, response.body());
  }

  private static HttpResponseMessage response(int status, String reason, String contentType, String body) {
    return new HttpResponseMessage(
        status,
        reason,
        contentType != null ? Map.of("Content-Type", contentType) : Map.of(),
        body != null ? body.getBytes(UTF_8) : null
    );
  }

  /// The authorization server metadata of RFC 8414, which is also the provider metadata of OpenID Connect Discovery
  /// section 3 if users can sign in
  private static String metadata(Keys keys, boolean signIn) {
    String issuer = keys.issuer().toString();
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("issuer", issuer);
    if (signIn)
      metadata.put("authorization_endpoint", issuer + "/authorize");
    metadata.put("token_endpoint", issuer + "/token");
    if (signIn)
      metadata.put("userinfo_endpoint", issuer + "/userinfo");
    metadata.put("jwks_uri", issuer + "/.well-known/jwks.json");
    metadata.put("registration_endpoint", issuer + "/clients");
    if (signIn) {
      metadata.put("scopes_supported", Authorizations.SupportedScopes);
      metadata.put("response_types_supported", List.of("code"));
      metadata.put("response_modes_supported", List.of("query"));
      metadata.put("grant_types_supported", List.of(Clients.AuthorizationCode, Clients.ClientCredentials));
      metadata.put("subject_types_supported", List.of("public"));
      metadata.put("id_token_signing_alg_values_supported", List.of("RS256"));
      metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
      metadata.put("code_challenge_methods_supported", List.of("S256"));
      metadata.put("claims_supported", List.of("iss", "sub", "aud", "exp", "iat", "auth_time", "nonce", "email", "email_verified", "name"));
      metadata.put("request_parameter_supported", false);
      metadata.put("request_uri_parameter_supported", false);
    } else {
      metadata.put("grant_types_supported", List.of(Clients.ClientCredentials));
      metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
      metadata.put("response_types_supported", List.of());
    }
    return DataType.objectMapper.writeValueAsString(metadata);
  }

}
