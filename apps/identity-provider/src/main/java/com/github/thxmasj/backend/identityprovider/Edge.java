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
import com.github.thxmasj.backend.identityprovider.Keys.Principal;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

/// The edge between the HTTP clients and the state machine. Credentials are handled here and nowhere else: client
/// secrets are hashed before a request enters the event log, generated secrets are added to responses after they leave
/// it, access tokens are signed on their way out and verified on their way in, and the secrets of imported clients are
/// verified against their BCrypt hashes. The event log only records hashes and the claims of the tokens.
///
/// The edge also serves what does not change with the requests: the health check, the public keys and the metadata.
final class Edge implements HttpInboxListener.Filter {

  private final Keys keys;
  private final String metadata;
  /// The BCrypt hashes of the secrets of the imported clients, by client id
  private final Map<String, String> importedSecretHashes;

  Edge(Keys keys, Map<String, String> importedSecretHashes) {
    this.keys = keys;
    this.metadata = metadata(keys);
    this.importedSecretHashes = Map.copyOf(importedSecretHashes);
  }

  @Override
  public Mono<HttpResponseMessage> filter(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    request = withoutTrustedHeaders(request);
    return switch (request.uri().getPath()) {
      case "/health" -> get(request, () -> response(200, "OK", "text/plain", "OK"));
      case "/.well-known/jwks.json" -> get(request, () -> response(200, "OK", "application/jwk-set+json", keys.publicKeys()));
      case "/.well-known/oauth-authorization-server" -> get(request, () -> response(200, "OK", "application/json", metadata));
      case "/token" -> post(request, next, this::token);
      case "/clients" -> post(request, next, this::register);
      default -> Mono.just(response(404, "Not Found", null, null));
    };
  }

  /// The token endpoint of RFC 6749 section 3.2. Only the client credentials grant is supported, with the client
  /// authenticated by HTTP Basic, which RFC 6749 section 2.3.1 requires every server to support.
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
    if (!grantType.equals("client_credentials"))
      return Mono.just(tokenError(400, "unsupported_grant_type", "Only the client_credentials grant is supported"));
    if (form.containsKey("scope") && !Scopes.isValid(form.get("scope")))
      return Mono.just(tokenError(400, "invalid_scope", "scope must be a space separated list of scopes"));
    if (!Clients.isValidClientId(clientId))
      return Mono.just(tokenError(401, "invalid_client", "Client authentication failed"));
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization"));
    headers.put(ClientIdHeader, clientId);
    headers.put(SecretHashHeader, secretHash(clientId, secret));
    return next.apply(new HttpRequestMessage(request.method(), request.uri(), headers, request.body()))
        .map(response -> switch (response.statusCode()) {
          case 200 -> tokenResponse(response);
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

  /// Registers a client on behalf of a client with the registration scope. The secret of the new client is generated
  /// here, so only its hash enters the event log.
  private Mono<HttpResponseMessage> register(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String authorization = request.headerValue("Authorization");
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7))
      return Mono.just(bearerError(401, null, null));
    Optional<Principal> principal = keys.verify(authorization.substring(7).trim());
    if (principal.isEmpty())
      return Mono.just(bearerError(401, "invalid_token", "The access token is invalid or expired"));
    if (!principal.get().scopes().contains(Clients.RegistrationScope))
      return Mono.just(bearerError(403, "insufficient_scope", "The access token lacks the scope " + Clients.RegistrationScope));
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

  /// Requests can't set the headers the edge tells the state machine what it has authenticated with
  private static HttpRequestMessage withoutTrustedHeaders(HttpRequestMessage request) {
    if (request.headers().keySet().stream().noneMatch(Edge::isTrusted))
      return request;
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(Edge::isTrusted);
    return new HttpRequestMessage(request.method(), request.uri(), headers, request.body());
  }

  private static boolean isTrusted(String header) {
    return Inbox.trustedHeaders.stream().anyMatch(header::equalsIgnoreCase);
  }

  private static Mono<HttpResponseMessage> get(HttpRequestMessage request, Supplier<HttpResponseMessage> response) {
    return Mono.just(request.method() == Method.GET ? response.get() : methodNotAllowed("GET"));
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
  private HttpResponseMessage bearerError(int status, String error, String description) {
    var challenge = new StringBuilder("Bearer realm=\"" + keys.issuer() + "\"");
    if (error != null)
      challenge.append(", error=\"").append(error).append("\", error_description=\"").append(description).append("\"");
    if (status == 403)
      challenge.append(", scope=\"").append(Clients.RegistrationScope).append("\"");
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

  /// The authorization server metadata of RFC 8414
  private static String metadata(Keys keys) {
    String issuer = keys.issuer().toString();
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("issuer", issuer);
    metadata.put("token_endpoint", issuer + "/token");
    metadata.put("jwks_uri", issuer + "/.well-known/jwks.json");
    metadata.put("registration_endpoint", issuer + "/clients");
    metadata.put("grant_types_supported", List.of("client_credentials"));
    metadata.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
    metadata.put("response_types_supported", List.of());
    return DataType.objectMapper.writeValueAsString(metadata);
  }

}
