package com.github.thxmasj.backend.backoffice;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.backoffice.Sessions.Failure;
import com.github.thxmasj.backend.backoffice.Sessions.Refusal;
import com.github.thxmasj.backend.backoffice.Sessions.SignIn;
import com.github.thxmasj.backend.backoffice.Sessions.Start;
import com.github.thxmasj.backend.backoffice.Sessions.User;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.nimbusds.jwt.JWTClaimsSet;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/// A backoffice for an application built with the HTTP inbox and the state machine: a web application where users,
/// signed in with OpenID Connect, look up the entities of the application and follow the graph of an entity, with its
/// event log, its ids, the events it waits for, and the entities it refers to and is referred to by, such as its inbox
/// and outbox requests. It only reads.
///
/// The backoffice is served under `/backoffice` by the application itself, so it reads the application's database
/// with the application's entity models. It takes three steps to add it:
///
/// ```java
/// var transitions = HttpInbox.transitions(concat(requests, Backoffice.requests()), withAll(application, Backoffice.transitions()));
/// var listener = new HttpInboxListener(stateMachine).filter(new Backoffice(config, dataSource, schema, transitions));
/// ```
///
/// Every sign-in of a user is an entity of the state machine, a `BackofficeSession`, whose event log tells who signed
/// in and which pages they viewed. The filter is the edge between the browser and the state machine: it signs the users
/// in with the provider, keeps the cookies out of the event log and renders the pages of the users the state machine
/// lets in.
public final class Backoffice implements HttpInboxListener.Filter {

  /// How long a user has to sign in with the provider
  private static final Duration signInLifetime = Duration.ofMinutes(10);
  private static final String sessionCookie = "backoffice_session";
  private static final String signInCookiePrefix = "backoffice_signin_";
  /// The errors of a provider shown to the user, OpenID Connect Core section 3.1.2.6. Others are shown as
  /// `server_error`, so the text of an attacker is not shown.
  private static final Set<String> providerErrors = Set.of(
      "access_denied", "temporarily_unavailable", "interaction_required", "login_required", "account_selection_required", "consent_required"
  );
  private static final Map<String, String> staticFiles = Map.of(
      "htmx.min.js", "text/javascript;charset=UTF-8",
      "backoffice.css", "text/css;charset=UTF-8"
  );
  private static final Map<String, String> securityHeaders = Map.of(
      "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      "X-Content-Type-Options", "nosniff",
      "Referrer-Policy", "same-origin"
  );

  private final Config config;
  private final OpenIdConnect provider;
  private final Pages pages;
  private final Map<String, byte[]> files;

  /// The backoffice as configured, or one which is not found, if `config` is null
  ///
  /// @param config      how the users sign in, or null if the application has no backoffice
  /// @param dataSource  the database of the application, which the backoffice reads with the role of the application
  /// @param schema      the schema of the state machine
  /// @param transitions the transitions given to the state machine, which give the entity models and event types
  public Backoffice(Config config, DataSource dataSource, String schema, Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    this(config, config != null ? Database.of(dataSource).client(dataSource) : null, schema, transitions);
  }

  /// @param client a client of the database of the application
  public Backoffice(Config config, DatabaseClient client, String schema, Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    this.config = config;
    if (config != null && !transitions.containsKey(Sessions.Session))
      throw new IllegalArgumentException("The transitions lack those of the backoffice, Backoffice.transitions()");
    this.provider = config != null ? new OpenIdConnect(config) : null;
    this.pages = config != null ? new Pages(new Graph(client, schema, transitions)) : null;
    this.files = config != null ? staticFiles.keySet().stream().collect(Collectors.toMap(name -> name, Backoffice::read)) : Map.of();
  }

  /// No backoffice: its pages are not found, and the other requests pass
  public static Backoffice none() {
    return new Backoffice(null, (DatabaseClient) null, null, Map.of());
  }

  /// The inbox requests of the backoffice, to add to those of the application's inbox
  public static List<HttpInboxRequest<?>> requests() {
    return Sessions.requests;
  }

  /// The transitions of the sessions of the backoffice, to add to those of the application
  public static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Sessions.transitions();
  }

  /// The entity model of the sessions, e.g. to draw their state diagram
  public static EntityModel sessionModel() {
    return Sessions.Session;
  }

  /// How the users sign in to the backoffice, and who may
  ///
  /// @param url             the URL of the application, under which the backoffice is at `/backoffice`, e.g.
  ///                        `https://idp.example.com`. Its `/backoffice/callback` is the redirect URI at the provider.
  /// @param issuer          the issuer of the OpenID Connect provider users sign in with, at which its discovery
  ///                        document is found
  /// @param clientId        the client id of the backoffice at the provider
  /// @param clientSecret    the client secret of the backoffice at the provider, or null for a public client
  /// @param scope           the scope requested from the provider, e.g. `openid email profile`
  /// @param claim           the claim of the ID token which permits a user to use the backoffice, e.g. `email`
  ///                        or `roles`
  /// @param claimValues     the values of the claim which permit a user, of which a claim with several values needs one
  /// @param sessionLifetime how long a user stays signed in
  public record Config(
      URI url,
      URI issuer,
      String clientId,
      String clientSecret,
      String scope,
      String claim,
      Set<String> claimValues,
      Duration sessionLifetime
  ) {

    public Config {
      String base = url.toString();
      url = URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
      claimValues = Set.copyOf(claimValues);
      if (claimValues.isEmpty())
        throw new IllegalArgumentException("A claim value is required, or no user could use the backoffice");
    }

    /// The configuration of the environment variables `BACKOFFICE_*`, or null if `BACKOFFICE_ISSUER` is not set, as
    /// the application then has no backoffice
    public static Config fromEnvironment(Map<String, String> env) {
      String issuer = optional(env, "BACKOFFICE_ISSUER");
      if (issuer == null)
        return null;
      return new Config(
          URI.create(required(env, "BACKOFFICE_URL")),
          URI.create(issuer),
          required(env, "BACKOFFICE_CLIENT_ID"),
          optional(env, "BACKOFFICE_CLIENT_SECRET"),
          env.getOrDefault("BACKOFFICE_SCOPE", "openid email profile"),
          required(env, "BACKOFFICE_CLAIM"),
          Arrays.stream(required(env, "BACKOFFICE_CLAIM_VALUES").split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()),
          Duration.parse(env.getOrDefault("BACKOFFICE_SESSION_LIFETIME", "PT8H"))
      );
    }

    private static String optional(Map<String, String> env, String name) {
      String value = env.get(name);
      return value == null || value.isBlank() ? null : value;
    }

    private static String required(Map<String, String> env, String name) {
      String value = optional(env, name);
      if (value == null)
        throw new IllegalArgumentException("Environment variable " + name + " is required with BACKOFFICE_ISSUER");
      return value;
    }

    /// Whether the cookies are only sent over HTTPS
    boolean secure() {
      return "https".equals(url.getScheme());
    }

  }

  @Override
  public Mono<HttpResponseMessage> filter(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    // Only the backoffice tells the state machine who signed in
    request = withoutHeaders(request, Sessions.trustedHeaders);
    String path = request.uri().getRawPath();
    if (path == null || !(path.equals(Sessions.Path) || path.startsWith(Sessions.Path + "/")))
      return next.apply(request);
    if (config == null)
      return Mono.just(response(404, "Not Found", "text/plain;charset=UTF-8", "Not Found\n"));
    // The cookies hold the token of the session and the secrets of sign-ins, so they are kept out of the event log
    String cookies = request.headerValue("Cookie");
    request = withoutHeaders(request, List.of("Cookie", "Authorization"));
    String rest = path.substring(Sessions.Path.length());
    if (rest.isEmpty())
      return Mono.just(redirect(Sessions.Path + "/", null));
    if (rest.startsWith("/static/"))
      return Mono.just(request.method() == Method.GET ? staticFile(rest.substring("/static/".length())) : methodNotAllowed("GET"));
    if (rest.equals("/callback"))
      return request.method() == Method.GET ? callback(request, cookies, next) : Mono.just(methodNotAllowed("GET"));
    if (rest.equals("/signout"))
      return request.method() == Method.POST ? signOut(cookies, next) : Mono.just(methodNotAllowed("POST"));
    if (rest.equals("/signin") || rest.startsWith("/signin/"))
      return Mono.just(response(404, "Not Found", "text/plain;charset=UTF-8", "Not Found\n"));
    return request.method() == Method.GET ? view(request, cookies, next) : Mono.just(methodNotAllowed("GET"));
  }

  /// A page, for the user of the session cookie. The state machine records the view and tells who the user is, or
  /// that the session has ended, in which case the user signs in again.
  private Mono<HttpResponseMessage> view(HttpRequestMessage request, String cookies, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String token = cookie(cookies, sessionCookie);
    if (token == null)
      return signIn(request, next);
    var headers = new HashMap<String, String>();
    headers.put(Sessions.TokenHashHeader, OpenIdConnect.hash(token));
    headers.put(Sessions.ViewIdHeader, UUID.randomUUID().toString());
    copyHeader(request, headers, "X-Correlation-Id");
    return next.apply(new HttpRequestMessage(Method.GET, request.uri(), headers, null))
        .flatMap(response -> {
          User user = user(response);
          if (user == null)
            return signIn(request, next).map(r -> withHeader(r, "Set-Cookie", sessionCookie("", Duration.ZERO)));
          Map<String, String> query;
          try {
            query = OpenIdConnect.parse(request.uri().getRawQuery());
          } catch (IllegalArgumentException e) {
            query = Map.of();
          }
          boolean fragment = request.headerValue("HX-Request") != null
              && request.headerValue("HX-Boosted") == null
              && request.headerValue("HX-History-Restore-Request") == null;
          return pages.page(request.uri().getRawPath(), query, user, fragment).map(page -> html(page.status(), page.html()));
        });
  }

  /// The user the state machine let in, or null if it did not
  private static User user(HttpResponseMessage response) {
    if (response.statusCode() != 200 || response.body() == null)
      return null;
    try {
      User user = DataType.objectMapper.readValue(response.body(), User.class);
      return user.subject() != null ? user : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /// Starts a sign-in: the state machine creates a session, and the user is sent to the provider with a secret kept in
  /// a cookie. A request of htmx, e.g. for a part of a page after the session expired, reloads the page instead, since
  /// htmx doesn't follow a redirect to another site.
  private Mono<HttpResponseMessage> signIn(HttpRequestMessage request, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    if (request.headerValue("HX-Request") != null) {
      var headers = noStore();
      headers.put("HX-Refresh", "true");
      return Mono.just(new HttpResponseMessage(401, "Unauthorized", headers, null));
    }
    String query = request.uri().getRawQuery();
    String returnPath = request.uri().getRawPath() + (query != null ? "?" + query : "");
    var headers = new HashMap<String, String>();
    headers.put("Content-Type", "application/json");
    copyHeader(request, headers, "X-Correlation-Id");
    var start = new HttpRequestMessage(Method.POST, URI.create(Sessions.Path + "/signin"), headers, DataType.objectMapper.writeValueAsBytes(new Start(returnPath)));
    return next.apply(start).flatMap(response -> {
      if (response.statusCode() != 200)
        return Mono.just(error(500, "The sign-in could not be started"));
      String session = DataType.objectMapper.readTree(response.body()).get("session").asString();
      String secret = OpenIdConnect.generateSecret();
      return provider.authorizationUrl(session, secret)
          .map(url -> redirect(url.toString(), signInCookie(session, secret, signInLifetime)))
          .onErrorResume(e -> {
            System.err.println("Backoffice sign-in failed to start: " + e);
            return Mono.just(error(502, "The identity provider can't be reached. Please try again later."));
          });
    });
  }

  /// The redirect URI at the provider. The code is redeemed here, and the user the provider signed in passed on to the
  /// state machine, which lets the user in if the claims permit it.
  private Mono<HttpResponseMessage> callback(HttpRequestMessage request, String cookies, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    Map<String, String> query;
    try {
      query = OpenIdConnect.parse(request.uri().getRawQuery());
    } catch (IllegalArgumentException e) {
      return Mono.just(error(400, "The response of the identity provider is invalid."));
    }
    String session = query.get("state");
    if (Graph.uuid(session) == null)
      return Mono.just(error(400, "The response of the identity provider is invalid."));
    String secret = cookie(cookies, signInCookiePrefix + session);
    if (secret == null)
      return Mono.just(error(400, "The sign-in has expired, or was started in another browser. Please sign in again."));
    var headers = new HashMap<String, String>();
    headers.put("Content-Type", "application/json");
    headers.put(Sessions.SessionHeader, session);
    copyHeader(request, headers, "X-Correlation-Id");
    if (query.containsKey("error") || !query.containsKey("code")) {
      String error = providerErrors.contains(query.get("error")) ? query.get("error") : "server_error";
      return next.apply(callbackRequest(headers, new Failure(error, "The sign-in failed at the identity provider: " + error)))
          .map(_ -> error(401, "The sign-in failed at the identity provider: " + error));
    }
    return provider.signIn(query.get("code"), secret)
        .flatMap(claims -> {
          User user = user(claims);
          String refusal = refusal(claims);
          if (refusal != null) {
            headers.put(Sessions.RefusedHeader, "true");
            return next.apply(callbackRequest(headers, new Refusal(user, refusal)))
                .map(_ -> error(403, "You are signed in as " + user.displayName() + ", who may not use the backoffice."));
          }
          String token = OpenIdConnect.generateSecret();
          headers.put(Sessions.TokenHashHeader, OpenIdConnect.hash(token));
          return next.apply(callbackRequest(headers, new SignIn(user, null, config.sessionLifetime().toSeconds())))
              .map(response -> {
                String returnPath = returnPath(response);
                if (response.statusCode() != 200 || returnPath == null)
                  return error(400, "The sign-in is invalid or has already been used. Please sign in again.");
                return redirect(returnPath, sessionCookie(token, config.sessionLifetime()));
              });
        })
        .onErrorResume(e -> {
          System.err.println("Backoffice sign-in failed for session " + session + ": " + e);
          return next.apply(callbackRequest(withoutHeaders(headers, List.of(Sessions.RefusedHeader, Sessions.TokenHashHeader)), new Failure("server_error", "The sign-in with the identity provider failed")))
              .map(_ -> error(502, "The sign-in with the identity provider failed. Please try again."));
        });
  }

  private static HttpRequestMessage callbackRequest(Map<String, String> headers, Object body) {
    return new HttpRequestMessage(Method.POST, URI.create(Sessions.Path + "/callback"), headers, DataType.objectMapper.writeValueAsBytes(body));
  }

  private static Map<String, String> withoutHeaders(Map<String, String> headers, Collection<String> names) {
    var result = new HashMap<>(headers);
    result.keySet().removeAll(names);
    return result;
  }

  /// The page the user returns to, which must be one of the backoffice, so the sign-in can't redirect elsewhere
  private static String returnPath(HttpResponseMessage response) {
    try {
      JsonNode path = DataType.objectMapper.readTree(response.body()).get("returnPath");
      if (path == null || !path.isString())
        return null;
      String p = path.asString();
      return p.startsWith(Sessions.Path + "/") && !p.contains("//") && !p.contains("\\") ? p : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static User user(JWTClaimsSet claims) {
    return new User(claims.getIssuer(), claims.getSubject(), string(claims, "name"), string(claims, "email"));
  }

  /// Why the claims don't permit the user to use the backoffice, or null if they do
  private String refusal(JWTClaimsSet claims) {
    Object value = claims.getClaim(config.claim());
    if (value == null)
      return "The ID token has no claim " + config.claim();
    var values = value instanceof Collection<?> collection ? collection : List.of(value);
    for (Object v : values) {
      if (v instanceof String s && config.claimValues().contains(s))
        return null;
    }
    return "The claim " + config.claim() + " has none of the permitted values";
  }

  private static String string(JWTClaimsSet claims, String name) {
    return claims.getClaim(name) instanceof String s ? s : null;
  }

  private Mono<HttpResponseMessage> signOut(String cookies, Function<HttpRequestMessage, Mono<HttpResponseMessage>> next) {
    String token = cookie(cookies, sessionCookie);
    Mono<HttpResponseMessage> signedOut = token == null ?
        Mono.empty() :
        next.apply(new HttpRequestMessage(
            Method.POST,
            URI.create(Sessions.Path + "/signout"),
            Map.of(Sessions.TokenHashHeader, OpenIdConnect.hash(token)),
            null
        ));
    return signedOut
        .then(Mono.fromSupplier(() -> withHeader(
            html(200, Pages.message("Signed out", "You have signed out of the backoffice.", "Sign in again")),
            "Set-Cookie",
            sessionCookie("", Duration.ZERO)
        )));
  }

  private HttpResponseMessage staticFile(String name) {
    byte[] content = files.get(name);
    if (content == null)
      return response(404, "Not Found", "text/plain;charset=UTF-8", "Not Found\n");
    var headers = new HashMap<>(securityHeaders);
    headers.put("Content-Type", staticFiles.get(name));
    headers.put("Cache-Control", "public, max-age=3600");
    return new HttpResponseMessage(200, "OK", headers, content);
  }

  private static byte[] read(String name) {
    try (InputStream in = Backoffice.class.getResourceAsStream(name)) {
      if (in == null)
        throw new IllegalStateException("The file " + name + " of the backoffice is missing");
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The cookie with the token of the session, sent with every page of the backoffice
  private String sessionCookie(String token, Duration maxAge) {
    return sessionCookie + "=" + token + "; Path=" + Sessions.Path + "; Max-Age=" + maxAge.toSeconds() + "; HttpOnly; SameSite=Lax" + (config.secure() ? "; Secure" : "");
  }

  /// The cookie with the secret of a sign-in, only sent to the callback. It is sent with the redirect from the provider,
  /// which is a top-level navigation, with SameSite=Lax. It expires, rather than being removed, as a response can't
  /// both remove it and set the cookie of the session.
  private String signInCookie(String session, String secret, Duration maxAge) {
    return signInCookiePrefix + session + "=" + secret + "; Path=" + Sessions.Path + "/callback; Max-Age=" + maxAge.toSeconds() + "; HttpOnly; SameSite=Lax" + (config.secure() ? "; Secure" : "");
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

  private static HttpRequestMessage withoutHeaders(HttpRequestMessage request, List<String> names) {
    if (request.headers().keySet().stream().noneMatch(header -> names.stream().anyMatch(header::equalsIgnoreCase)))
      return request;
    var headers = new HashMap<>(request.headers());
    headers.keySet().removeIf(header -> names.stream().anyMatch(header::equalsIgnoreCase));
    return new HttpRequestMessage(request.method(), request.uri(), headers, request.body());
  }

  private static void copyHeader(HttpRequestMessage request, Map<String, String> headers, String name) {
    String value = request.headerValue(name);
    if (value != null)
      headers.put(name, value);
  }

  private static HttpResponseMessage html(int status, String html) {
    var headers = noStore();
    headers.put("Content-Type", "text/html;charset=UTF-8");
    return new HttpResponseMessage(status, reasonPhrase(status), headers, html.getBytes(UTF_8));
  }

  private static HttpResponseMessage error(int status, String message) {
    return html(status, Pages.message("Sign-in failed", message, "Sign in again"));
  }

  private static HttpResponseMessage redirect(String location, String cookie) {
    var headers = noStore();
    headers.put("Location", location);
    if (cookie != null)
      headers.put("Set-Cookie", cookie);
    return new HttpResponseMessage(303, "See Other", headers, null);
  }

  private static HttpResponseMessage methodNotAllowed(String allowed) {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Allow", allowed);
    return new HttpResponseMessage(405, "Method Not Allowed", headers, null);
  }

  private static HttpResponseMessage response(int status, String reason, String contentType, String body) {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Content-Type", contentType);
    return new HttpResponseMessage(status, reason, headers, body.getBytes(UTF_8));
  }

  private static HttpResponseMessage withHeader(HttpResponseMessage response, String name, String value) {
    var headers = new HashMap<>(response.headers());
    headers.put(name, value);
    return new HttpResponseMessage(response.statusCode(), response.reasonPhrase(), headers, response.body());
  }

  private static HashMap<String, String> noStore() {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Cache-Control", "no-store");
    return headers;
  }

  private static String reasonPhrase(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 500 -> "Internal Server Error";
      case 502 -> "Bad Gateway";
      default -> "Status " + status;
    };
  }

}
