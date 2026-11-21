package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.inbox.AccessTokenVerifier.AccessToken;
import com.github.thxmasj.backend.http.inbox.Form;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/// The endpoints of the identity provider: what the inbox requests do with the credentials of the requests, and the
/// requests answered at once. Credentials are handled in the session of a request and kept out of the event log:
/// client secrets are hashed by the content parsers, generated secrets and codes only reach the responses, which the
/// event log masks, tokens are signed by the response creators and verified by the content parsers, and the secrets of
/// imported clients are verified against their BCrypt hashes. The event log only records hashes, the claims of the
/// tokens and the claims of the users signed in.
///
/// The requests answered at once, such as the health check, the public keys, the metadata, and the requests refused
/// before they reach the entities of the identity provider, e.g. without client authentication, are not stored.
final class Endpoints {

  /// The cookie holding the secret of a sign-in with the upstream provider, by the authorization it is for
  static final String cookiePrefix = "idp_authorization_";
  /// How long a user has to sign in with the upstream provider
  private static final Duration signInLifetime = Duration.ofMinutes(10);
  /// The value of [Inbox#InternalHeader] of the registrations of the identity provider itself, which no other request
  /// knows, as it is generated for the process and masked in the event log
  private static final String internalKey = Secrets.generate();

  private final Keys keys;
  private final OpenIdConnectClient upstream;
  private final String metadata;
  /// The BCrypt hashes of the secrets of the imported clients, by client id
  private final Map<String, String> importedSecretHashes;

  /// @param keys     the signing keys, or null for an inbox only giving its entity models
  /// @param upstream the client of the provider users sign in with, or null if users can't sign in
  Endpoints(Keys keys, OpenIdConnectClient upstream, Map<String, String> importedSecretHashes) {
    this.keys = keys;
    this.upstream = upstream;
    this.metadata = keys != null ? metadata(keys, upstream != null) : null;
    this.importedSecretHashes = Map.copyOf(importedSecretHashes);
  }

  OpenIdConnectClient upstream() {
    return upstream;
  }

  /// The answers of the requests answered at once, by the request, as the inbox request of the requests answered at once
  /// asks for it both when matching a request and when parsing it. They are kept while the request is. A request which
  /// is processed is checked again by its content parser, e.g. its access token verified, so an answer of an equal
  /// request kept a while longer lets nothing through.
  private final Map<HttpRequest<byte[]>, Optional<HttpResponse<byte[]>>> answers = Collections.synchronizedMap(new WeakHashMap<>());

  /// The response of a request answered at once, or null for a request for the entities of the identity provider
  HttpResponse<byte[]> immediate(HttpRequest<byte[]> request) {
    return answers.computeIfAbsent(request, r -> Optional.ofNullable(answer(r))).orElse(null);
  }

  /// The access token, if it is valid. It is verified each time, rather than remembered, so that an expired token is
  /// never taken as valid.
  private Optional<AccessToken> verify(String token) {
    return keys.verify(token);
  }

  private HttpResponse<byte[]> answer(HttpRequest<byte[]> request) {
    String path = request.uri().getPath();
    if (upstream != null) {
      switch (path) {
        case "/.well-known/openid-configuration" -> {
          return get(request, () -> response(200, "OK", "application/json", metadata));
        }
        case "/authorize" -> {
          if (request.method() != Method.GET)
            return methodNotAllowed("GET");
          // A request without a valid client id and redirect URI can't be redirected to the client
          return Inbox.authorizationRequest(request).isInvalid() ? errorPage("The sign-in request is invalid or has already been used") : null;
        }
        case "/callback" -> {
          return request.method() == Method.GET ? callbackRefusal(request) : methodNotAllowed("GET");
        }
        case "/userinfo" -> {
          return request.method() == Method.GET ? userInfoRefusal(request) : methodNotAllowed("GET");
        }
        default -> {}
      }
    }
    return switch (path) {
      case "/health" -> get(request, () -> response(200, "OK", "text/plain", "OK"));
      case "/.well-known/jwks.json" -> get(request, () -> response(200, "OK", "application/jwk-set+json", keys.publicKeys()));
      case "/.well-known/oauth-authorization-server" -> get(request, () -> response(200, "OK", "application/json", metadata));
      case "/token" -> request.method() == Method.POST ? tokenRefusal(request) : methodNotAllowed("POST");
      case "/clients" -> request.method() != Method.POST ? methodNotAllowed("POST") : isInternal(request) ? null : registrationRefusal(request);
      default -> response(404, "Not Found", null, null);
    };
  }

  // The token endpoint

  /// The refusal of a request to the token endpoint of RFC 6749 section 3.2 which is invalid or isn't authenticated by
  /// HTTP Basic, which RFC 6749 section 2.3.1 requires every server to support, or null if it is to be processed
  private HttpResponse<byte[]> tokenRefusal(HttpRequest<byte[]> request) {
    String contentType = request.headerValue("Content-Type");
    if (contentType == null || !contentType.toLowerCase().startsWith("application/x-www-form-urlencoded"))
      return tokenError(400, "invalid_request", "Content-Type must be application/x-www-form-urlencoded");
    Map<String, String> form;
    try {
      form = Form.parse(request.body());
    } catch (IllegalArgumentException e) {
      return tokenError(400, "invalid_request", e.getMessage());
    }
    // Only the Authorization header is masked in the event log, not a secret in the body
    if (form.containsKey("client_secret") || form.containsKey("client_assertion"))
      return tokenError(400, "invalid_request", "Clients must authenticate with HTTP Basic");
    Optional<String[]> credentials = basicCredentials(request.headerValue("Authorization"));
    if (credentials.isEmpty())
      return tokenError(401, "invalid_client", "Clients must authenticate with HTTP Basic");
    String clientId = credentials.get()[0];
    if (form.containsKey("client_id") && !form.get("client_id").equals(clientId))
      return tokenError(400, "invalid_request", "client_id does not match the authenticated client");
    String grantType = form.get("grant_type");
    if (grantType == null)
      return tokenError(400, "invalid_request", "grant_type is missing");
    if (!grantType.equals(Clients.ClientCredentials) && !grantType.equals(Clients.AuthorizationCode))
      return tokenError(400, "unsupported_grant_type", "Only the client_credentials and authorization_code grants are supported");
    if (grantType.equals(Clients.ClientCredentials) && form.containsKey("scope") && !Scopes.isValid(form.get("scope")))
      return tokenError(400, "invalid_scope", "scope must be a space separated list of scopes");
    if (grantType.equals(Clients.AuthorizationCode)) {
      String code = form.get("code");
      String verifier = form.get("code_verifier");
      if (code == null || code.isEmpty())
        return tokenError(400, "invalid_request", "code is missing");
      if (form.get("redirect_uri") == null)
        return tokenError(400, "invalid_request", "redirect_uri is missing");
      if (verifier != null && !Inbox.codeChallengePattern.matcher(verifier).matches())
        return tokenError(400, "invalid_request", "code_verifier is invalid");
    }
    if (!Clients.isValidClientId(clientId))
      return tokenError(401, "invalid_client", "Client authentication failed");
    return null;
  }

  /// Whether a request to the token endpoint, which [#tokenRefusal(HttpRequest)] has let through, redeems a code
  static boolean isCodeRequest(HttpRequest<byte[]> request) {
    try {
      return Clients.AuthorizationCode.equals(Form.parse(request.body()).get("grant_type"));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /// A token request of the client credentials grant, with the hash of the secret of the client
  Validated<Tokens.Request> tokenRequest(HttpRequest<byte[]> request) {
    String[] credentials = basicCredentials(request.headerValue("Authorization")).orElseThrow();
    return valid(new Tokens.Request(credentials[0], secretHash(credentials[0], credentials[1]), Form.parse(request.body()).get("scope")));
  }

  /// A token request of the authorization code grant, with the hashes of the secret of the client, the code and the
  /// code verifier, which is the S256 challenge of the verifier
  Validated<Tokens.CodeRequest> codeRequest(HttpRequest<byte[]> request) {
    String[] credentials = basicCredentials(request.headerValue("Authorization")).orElseThrow();
    Map<String, String> form = Form.parse(request.body());
    String verifier = form.get("code_verifier");
    return valid(new Tokens.CodeRequest(
        credentials[0],
        secretHash(credentials[0], credentials[1]),
        Secrets.hash(form.get("code")),
        form.get("redirect_uri"),
        verifier != null ? Secrets.hash(verifier) : null
    ));
  }

  /// The hash the state machine compares with that of the client. The secret of an imported client is verified here
  /// against its BCrypt hash, see [ImportedClients].
  private String secretHash(String clientId, String secret) {
    String bcryptHash = importedSecretHashes.get(clientId);
    return bcryptHash != null ? ImportedClients.secretHash(secret, bcryptHash) : Secrets.hash(secret);
  }

  /// The access token response of RFC 6749 section 5.1, with the token signed from the claims the state machine
  /// issued, or the error response with the challenge of HTTP Basic if the client failed to authenticate
  HttpResponse<byte[]> tokenResponse(TransitionContext<Reply> c) {
    HttpResponse<byte[]> issued = Inbox.reply(c);
    return switch (issued.statusCode()) {
      case 200 -> {
        var token = DataType.objectMapper.readValue(issued.body(), Tokens.IssuedToken.class);
        var body = new LinkedHashMap<String, Object>();
        body.put("access_token", keys.sign(token));
        body.put("token_type", "Bearer");
        body.put("expires_in", token.expiresAt() - token.issuedAt());
        body.put("scope", token.scope());
        yield new HttpResponse<>(200, "OK", noStore(issued.headers()), DataType.objectMapper.writeValueAsBytes(body));
      }
      case 401 -> withHeader(issued, "WWW-Authenticate", basicChallenge());
      default -> issued;
    };
  }

  /// The access token response of RFC 6749 section 5.1 with the ID token of OpenID Connect Core section 3.1.3.3
  HttpResponse<byte[]> userTokensResponse(TransitionContext<Reply> c) {
    HttpResponse<byte[]> issued = Inbox.reply(c);
    return switch (issued.statusCode()) {
      case 200 -> {
        var tokens = DataType.objectMapper.readValue(issued.body(), Tokens.UserTokens.class);
        var body = new LinkedHashMap<String, Object>();
        body.put("access_token", keys.sign(tokens.accessToken()));
        body.put("token_type", "Bearer");
        body.put("expires_in", tokens.accessToken().expiresAt() - tokens.accessToken().issuedAt());
        body.put("scope", tokens.accessToken().scope());
        body.put("id_token", keys.sign(tokens.idToken()));
        yield new HttpResponse<>(200, "OK", noStore(issued.headers()), DataType.objectMapper.writeValueAsBytes(body));
      }
      case 401 -> withHeader(issued, "WWW-Authenticate", basicChallenge());
      default -> issued;
    };
  }

  // The registration endpoint

  /// The refusal of a registration without an access token with the registration scope, or null
  private HttpResponse<byte[]> registrationRefusal(HttpRequest<byte[]> request) {
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return bearerError(401, null, null, null);
    Optional<AccessToken> principal = verify(authorization.substring(7).trim());
    if (principal.isEmpty())
      return bearerError(401, "invalid_token", "The access token is invalid or expired", null);
    if (!principal.get().scopes().contains(Clients.RegistrationScope))
      return bearerError(403, "insufficient_scope", "The access token lacks the scope " + Clients.RegistrationScope, Clients.RegistrationScope);
    return null;
  }

  /// A registration on behalf of the client of the access token. The secret of the new client is generated here, so
  /// only its hash enters the event log, and the secret the response, which the event log masks.
  Validated<Clients.RegistrationRequest> registration(HttpRequest<byte[]> request) {
    Optional<AccessToken> verified = verify(request.headerValue("Authorization").substring(7).trim())
        .filter(token -> token.scopes().contains(Clients.RegistrationScope));
    // Checked by the answer at once, unless the token has expired since
    if (verified.isEmpty())
      return invalid("The access token is invalid or expired");
    AccessToken principal = verified.get();
    String secret = Secrets.generate();
    Validated<Clients.Registration> registration = Inbox.registration(request, principal.clientId(), Secrets.hash(secret));
    return registration.isInvalid() ?
        invalid(registration.invalidReason()) :
        valid(new Clients.RegistrationRequest(registration.validValue(), secret));
  }

  /// The client information response of RFC 7591 section 3.2.1, with the secret, which is not to be cached
  static HttpResponse<byte[]> registered(TransitionContext<Reply> c) {
    HttpResponse<byte[]> registered = Inbox.reply(c);
    if (registered.statusCode() != 201)
      return registered;
    return new HttpResponse<>(201, "Created", noStore(registered.headers()), registered.body());
  }

  /// A registration by the identity provider itself, e.g. of the bootstrap client, with the hash of the secret of the
  /// client, as the registrar
  static HttpRequest<byte[]> internalRegistration(String registeredBy, String secretHash, byte[] body) {
    var headers = Map.of(
        "Content-Type", "application/json",
        Inbox.InternalHeader, internalKey,
        Inbox.RegisteredByHeader, registeredBy,
        Inbox.SecretHashHeader, secretHash
    );
    return new HttpRequest<>(Method.POST, URI.create("/clients"), headers, body);
  }

  /// Whether the request is a registration of the identity provider itself
  static boolean isInternal(HttpRequest<byte[]> request) {
    return internalKey.equals(request.headerValue(Inbox.InternalHeader));
  }

  // Signing users in

  /// The authorization endpoint of RFC 6749 section 3.1. The state machine checks the request, and the user is
  /// redirected to the upstream provider to sign in, or back to the client with an error. The secret of the sign-in
  /// with the upstream provider is generated here and kept in a cookie, which binds it to the browser the user started
  /// it in, and which the event log masks.
  HttpResponse<byte[]> authorizationResponse(TransitionContext<Reply> c) {
    HttpResponse<byte[]> response = Inbox.reply(c);
    return switch (response.statusCode()) {
      case 200 -> {
        String authorization = DataType.objectMapper.readTree(response.body()).get("authorization").asString();
        String secret = Secrets.generate();
        // The upstream provider is discovered when the identity provider starts, so the URL is there at once
        URI url = upstream.authorizationUrl(authorization, secret).toFuture().getNow(null);
        if (url == null)
          throw new IllegalStateException("The upstream provider is not discovered");
        var headers = noStore(Map.of());
        headers.remove("Content-Type");
        headers.put("Location", url.toString());
        headers.put("Set-Cookie", cookie(authorization, secret, signInLifetime));
        yield new HttpResponse<>(302, "Found", headers, null);
      }
      case 302 -> redirect(response);
      default -> errorPage(response);
    };
  }

  /// The refusal of a return from the upstream provider which isn't to an authorization, or is in another browser than
  /// the sign-in was started in, or null
  private static HttpResponse<byte[]> callbackRefusal(HttpRequest<byte[]> request) {
    Map<String, String> query;
    try {
      query = query(request);
    } catch (IllegalArgumentException e) {
      return errorPage("The response of the identity provider is invalid");
    }
    String authorization = query.get("state");
    try {
      UUID.fromString(authorization != null ? authorization : "");
    } catch (IllegalArgumentException e) {
      return errorPage("The response of the identity provider is invalid");
    }
    if (cookie(request.headerValue("Cookie"), cookiePrefix + authorization) == null)
      return errorPage("The sign-in has expired, or was started in another browser. Please sign in again.");
    return null;
  }

  /// The return of the user from the upstream provider, with the code generated for the client, which only its
  /// redirect to the client has
  static Validated<Authorizations.Callback> callback(HttpRequest<byte[]> request) {
    Map<String, String> query = query(request);
    String authorization = query.get("state");
    return valid(new Authorizations.Callback(
        UUID.fromString(authorization),
        query.get("code"),
        query.containsKey("error") ? query.get("error") : null,
        cookie(request.headerValue("Cookie"), cookiePrefix + authorization),
        Secrets.generate()
    ));
  }

  /// The redirect of the user to the client with the code, or the error page, and the cookie of the sign-in cleared
  HttpResponse<byte[]> callbackResponse(TransitionContext<Reply> c) {
    HttpResponse<byte[]> response = Inbox.reply(c);
    HttpResponse<byte[]> answer = response.statusCode() == 302 ? redirect(response) : errorPage(response);
    String authorization = c.log().oneIfExists(HttpInbox.RequestAccepted)
        .map(accepted -> query(accepted.t1()).get("state"))
        .orElseThrow(() -> new IllegalStateException("The callback of the response is not in the session"));
    return withHeader(answer, "Set-Cookie", cookie(authorization, "", Duration.ZERO));
  }

  private static Map<String, String> query(HttpRequest<byte[]> request) {
    String raw = request.uri().getRawQuery();
    return Form.parse(raw != null ? raw.getBytes(UTF_8) : null);
  }

  /// The refusal of a request to the UserInfo endpoint of OpenID Connect Core section 5.3 without a valid access
  /// token with the scope `openid`, or null
  private HttpResponse<byte[]> userInfoRefusal(HttpRequest<byte[]> request) {
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return bearerError(401, null, null, null);
    String token = authorization.substring(7).trim();
    Optional<AccessToken> principal = verify(token);
    Optional<String> tokenId = principal.flatMap(_ -> Keys.tokenId(token));
    if (tokenId.isEmpty())
      return bearerError(401, "invalid_token", "The access token is invalid or expired", null);
    if (!principal.get().scopes().contains("openid"))
      return bearerError(403, "insufficient_scope", "The access token lacks the scope openid", "openid");
    return null;
  }

  /// The `jti` of the verified access token of a UserInfo request, the entity id of the token request which issued it
  Validated<String> tokenId(HttpRequest<byte[]> request) {
    String token = request.headerValue("Authorization").substring(7).trim();
    return verify(token).flatMap(_ -> Keys.tokenId(token)).map(Validated::valid).orElseGet(() -> invalid("Unauthenticated"));
  }

  /// The UserInfo response, or the refusal of a token which is not one of a user, e.g. of the client credentials grant
  HttpResponse<byte[]> userInfoResponse(TransitionContext<Reply> c) {
    HttpResponse<byte[]> response = Inbox.reply(c);
    return response.statusCode() == 200 ?
        new HttpResponse<>(200, "OK", noStore(response.headers()), response.body()) :
        bearerError(401, "invalid_token", "The access token is not one of a user", null);
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

  private static HttpResponse<byte[]> get(HttpRequest<byte[]> request, Supplier<HttpResponse<byte[]>> response) {
    return request.method() == Method.GET ? response.get() : methodNotAllowed("GET");
  }

  /// The redirect of the user to the client, which the state machine has made, with the code if the user signed in
  private static HttpResponse<byte[]> redirect(HttpResponse<byte[]> response) {
    var redirect = DataType.objectMapper.readValue(response.body(), Inbox.Redirect.class);
    String uri = redirect.redirectUri();
    var headers = noStore(Map.of());
    headers.remove("Content-Type");
    headers.put("Location", uri + (uri.contains("?") ? "&" : "?") + Form.encode(redirect.parameters()));
    return new HttpResponse<>(302, "Found", headers, null);
  }

  /// A page for the user, when the client can't be redirected to, from the error response of the state machine
  private static HttpResponse<byte[]> errorPage(HttpResponse<byte[]> response) {
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

  private static HttpResponse<byte[]> errorPage(String description) {
    var headers = noStore(Map.of());
    headers.put("Content-Type", "text/plain;charset=UTF-8");
    return new HttpResponse<>(400, "Bad Request", headers, ("Sign-in failed: " + description + "\n").getBytes(UTF_8));
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

  private static HttpResponse<byte[]> methodNotAllowed(String allowed) {
    return new HttpResponse<>(405, "Method Not Allowed", Map.of("Allow", allowed), null);
  }

  private HttpResponse<byte[]> tokenError(int status, String error, String description) {
    var body = new LinkedHashMap<String, String>();
    body.put("error", error);
    body.put("error_description", description);
    var headers = noStore(Map.of());
    if (status == 401)
      headers.put("WWW-Authenticate", basicChallenge());
    return new HttpResponse<>(status, status == 401 ? "Unauthorized" : "Bad Request", headers, DataType.objectMapper.writeValueAsBytes(body));
  }

  private String basicChallenge() {
    return "Basic realm=\"" + keys.issuer() + "\", charset=\"UTF-8\"";
  }

  /// The error response of RFC 6750 section 3
  ///
  /// @param scope the scope required, for insufficient_scope
  private HttpResponse<byte[]> bearerError(int status, String error, String description, String scope) {
    var challenge = new StringBuilder("Bearer realm=\"" + keys.issuer() + "\"");
    if (error != null)
      challenge.append(", error=\"").append(error).append("\", error_description=\"").append(description).append("\"");
    if (scope != null)
      challenge.append(", scope=\"").append(scope).append("\"");
    return new HttpResponse<>(status, status == 401 ? "Unauthorized" : "Forbidden", Map.of("WWW-Authenticate", challenge.toString()), null);
  }

  private static Map<String, String> noStore(Map<String, String> headers) {
    var result = new HashMap<>(headers);
    result.put("Content-Type", "application/json;charset=UTF-8");
    result.put("Cache-Control", "no-store");
    result.put("Pragma", "no-cache");
    return result;
  }

  private static HttpResponse<byte[]> withHeader(HttpResponse<byte[]> response, String name, String value) {
    var headers = new HashMap<>(response.headers());
    headers.put(name, value);
    return new HttpResponse<>(response.statusCode(), response.reasonPhrase(), headers, response.body());
  }

  private static HttpResponse<byte[]> response(int status, String reason, String contentType, String body) {
    return new HttpResponse<>(
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
