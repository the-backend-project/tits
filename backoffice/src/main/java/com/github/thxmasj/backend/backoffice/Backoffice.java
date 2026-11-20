package com.github.thxmasj.backend.backoffice;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.http.HttpClient;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/// A backoffice for an application built with the HTTP inbox and the state machine: a web application where users,
/// signed in with OpenID Connect, look up the entities of the application and follow the graph of an entity, with its
/// event log, its ids, the events it waits for, and the entities it refers to and is referred to by, such as its inbox
/// and outbox requests. It only reads the application.
///
/// The backoffice is an application of its own, deployed apart from the application it looks into. It is a state
/// machine of its own, in a schema of its own, whose model this class gives: every request it receives is an inbox
/// request, and every decision a transition, from signing users in with the provider to rendering the pages. Every
/// sign-in is a `BackofficeSession`, whose event log tells who signed in and which pages they viewed. It reads the
/// schema of the application, with the application's entity models, with a role which may only read it.
/// [BackofficeApp] runs it:
///
/// ```java
/// BackofficeApp.run("Orders", "orders", Orders.Inbox.entityModels(), args, System.getenv());
/// ```
public final class Backoffice {

  private final Sessions sessions;

  /// @param service                the name of the application, e.g. `Identity provider`, after which the backoffice is
  ///                               named in the header and the title of its pages, e.g. "Identity provider backoffice"
  /// @param config                 how the users sign in
  /// @param client                 a client of the database, whose role may read the schema of the application
  /// @param applicationSchema      the schema of the application's state machine
  /// @param applicationModels      the entity models given to the application's state machine, which with those their
  ///                               transitions trigger events on give the entity models and
  ///                               event types of the entities read
  public Backoffice(
      String service,
      Config config,
      DatabaseClient client,
      String applicationSchema,
      List<? extends EntityModel> applicationModels
  ) {
    this(service, config, client, applicationSchema, applicationModels, new JdkHttpClient());
  }

  /// @param forwarder the HTTP client the token requests to the provider are sent with
  Backoffice(
      String service,
      Config config,
      DatabaseClient client,
      String applicationSchema,
      List<? extends EntityModel> applicationModels,
      HttpClient forwarder
  ) {
    Objects.requireNonNull(config, "config");
    var pages = new Pages(new Graph(client, applicationSchema, applicationModels), service + " backoffice");
    this.sessions = new Sessions(config, openIdConnectClient(config), pages, Sessions.files(), forwarder);
  }

  /// The provider is discovered when the first user signs in, so the application may be its own provider
  private static OpenIdConnectClient openIdConnectClient(Config config) {
    return new OpenIdConnectClient(new OpenIdConnectClient.Config(
        config.issuer(),
        config.clientId(),
        config.clientSecret(),
        config.scope(),
        URI.create(config.url() + Sessions.Path + "/callback")
    ));
  }

  /// The inbox of the backoffice's state machine, whose entity models the state machine is created with. Its requests
  /// trigger events on the sessions and the other requests, which the state machine thereby runs too.
  public HttpInbox inbox() {
    return sessions.inbox();
  }

  /// The entity model of the sessions, e.g. to draw their state diagram
  public EntityModel sessionModel() {
    return sessions.session();
  }

  /// How the users sign in to the backoffice, and who may
  ///
  /// @param url             the URL the backoffice is reached at, under which its pages are at `/backoffice`, e.g.
  ///                        `https://idp-backoffice.example.com`. Its `/backoffice/callback` is the redirect URI at the
  ///                        provider.
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

    /// The configuration of the environment variables `BACKOFFICE_*`
    public static Config fromEnvironment(Map<String, String> env) {
      String issuer = required(env, "BACKOFFICE_ISSUER");
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
        throw new IllegalArgumentException("Environment variable " + name + " is required");
      return value;
    }

    /// Whether the cookies are only sent over HTTPS
    boolean secure() {
      return "https".equals(url.getScheme());
    }

  }

}
