package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.DataSourceBuilder;
import com.github.thxmasj.backend.core.database.SqlDatabase;
import com.github.thxmasj.backend.http.HttpMessageParser;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.identityprovider.Config.BootstrapClient;
import com.github.thxmasj.backend.identityprovider.ImportedClients.ImportedClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.netty.DisposableServer;

/// An OAuth 2.0 authorization server for the client credentials grant, and an OpenID Connect provider for the
/// authorization code flow, built with the HTTP inbox and the state machine. Clients are registered by other clients,
/// and the first one is bootstrapped from the configuration. Users sign in with an upstream OpenID Connect provider.
///
/// Usage:
///
/// - `identity-provider` serves HTTP on `$PORT` until terminated.
/// - `identity-provider migrate` creates the database schema and exits. Run it as a one-off admin process before serving.
/// - `identity-provider generate-key` prints a JWK set with a new signing key, for `$SIGNING_KEYS`.
///
/// A process which resolves the configuration otherwise, e.g. the signing keys from a key vault, runs the identity
/// provider with [#run(String\[\], Map)] and the environment it has resolved.
public final class IdentityProvider {

  private IdentityProvider() {}

  public static void main(String[] args) {
    run(args, System.getenv());
  }

  /// Runs a command as [#main(String\[\])] does, configured by the environment variables of `env` rather than of the
  /// process. Exits the process if serving fails to start.
  public static void run(String[] args, Map<String, String> env) {
    long t0 = System.nanoTime();
    String command = args.length == 0 ? "serve" : args[0];
    if (command.equals("generate-key")) {
      System.out.println(Keys.generate());
      return;
    }
    Config config = Config.fromEnvironment(env);
    switch (command) {
      case "serve" -> {
        // Invalid keys fail before connecting to the database
        Keys keys = keys(config.requireServing(), Clock.systemUTC());
        HttpInbox inbox = Inbox.inbox(config.tokenLifetime());
        StateMachine stateMachine = stateMachine(config, inbox);
        DisposableServer server;
        try {
          if (config.bootstrapClient() != null)
            bootstrap(stateMachine, inbox, config.bootstrapClient());
          importClients(stateMachine, inbox, config.clients());
          OpenIdConnectClient upstream = config.upstream() != null ? upstream(config.upstream(), config.issuer()) : null;
          server = serve(stateMachine, inbox, keys, upstream, config.port(), config.clients());
        } catch (RuntimeException e) {
          // The workers of the state machine would keep the process running without serving
          e.printStackTrace();
          System.exit(1);
          return;
        }
        System.out.println("Listening on port " + server.port() + " after " + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms");
        // A container runtime stops the process with SIGTERM, which runs the shutdown hooks
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          server.disposeNow(Duration.ofSeconds(10));
          System.out.println("Stopped listening");
          try {
            stateMachine.close();
            System.out.println("Stopped state machine");
          } catch (Exception e) {
            System.err.println("Failed to stop state machine: " + e);
          }
        }));
        server.onDispose().block();
      }
      case "migrate" -> {
        migrate(config);
        System.out.println("Migrated schema " + config.schema());
      }
      default -> {
        System.err.println("Usage: identity-provider [serve|migrate|generate-key]");
        System.exit(2);
      }
    }
  }

  /// Creates the schema if it does not exist, and fails if it exists with a different structure.
  public static void migrate(Config config) {
    if (config.role() == null)
      throw new IllegalArgumentException("Environment variable DATABASE_ROLE is required for migration");
    SqlDatabase database = SqlDatabase.named(config.database());
    database.createSchema(new DataSourceBuilder(database, config.databaseConfig()).build(), config.schema(), config.role())
        .timeout(Duration.ofSeconds(30))
        .block();
  }

  /// @param inbox the inbox of the identity provider, see [Inbox#inbox(Duration)]
  public static StateMachine stateMachine(Config config, HttpInbox inbox) {
    SqlDatabase database = SqlDatabase.named(config.database());
    return new StateMachine(
        null,
        inbox.entityModels(),
        new DataSourceBuilder(database, config.databaseConfig()).buildPooled(),
        null, // The schema is created by the migrate admin process
        config.schema(),
        null,
        Clock.systemUTC(),
        // Writes the events to stdout for monitoring, e.g. the refused token requests as warnings
        new JsonLinesListener()
    );
  }

  static Keys keys(Config config, Clock clock) {
    return new Keys(config.signingKeys(), config.issuer(), config.audience(), clock);
  }

  /// The client of the upstream provider, which is discovered at once, so that the identity provider fails to start
  /// if the upstream provider is misconfigured or can't be reached
  static OpenIdConnectClient upstream(Config.Upstream upstream, URI issuer) {
    var client = new OpenIdConnectClient(upstream.client(issuer));
    client.discover()
        .onErrorMap(e -> new IllegalStateException("The upstream provider of UPSTREAM_ISSUER can't be used: " + e.getMessage(), e))
        .block();
    return client;
  }

  /// @param upstream the client of the provider users sign in with, or null if users can't sign in
  static DisposableServer serve(StateMachine stateMachine, HttpInbox inbox, Keys keys, OpenIdConnectClient upstream, int port, List<ImportedClient> importedClients) {
    var importedSecretHashes = importedClients.stream().collect(toMap(ImportedClient::clientId, ImportedClient::secretHash));
    return new HttpInboxListener(stateMachine, inbox).filter(new Edge(keys, upstream, importedSecretHashes)).listen(port);
  }

  /// Registers the bootstrap client with the registration scope, unless its client id is taken. A taken client id is
  /// most likely the bootstrap client registered by an earlier start, whose secret is kept.
  public static void bootstrap(StateMachine stateMachine, HttpInbox inbox, BootstrapClient client) {
    var body = Map.of("client_id", client.id(), "scope", Clients.RegistrationScope);
    HttpResponse<byte[]> response = register(stateMachine, inbox, "bootstrap", body, client.id(), Secrets.hash(client.secret()));
    switch (response.statusCode()) {
      case 201 -> System.out.println("Registered bootstrap client " + client.id());
      case 409 -> System.out.println("Bootstrap client " + client.id() + " is already registered. Its secret is not changed.");
      default -> throw new IllegalStateException(
          "Failed to register bootstrap client " + client.id() + ": " + response.statusCode() + " " + new String(response.body(), UTF_8)
      );
    }
  }

  /// Registers the imported clients whose client ids are not taken. A registered client is kept as is, whatever the
  /// configuration says now.
  static void importClients(StateMachine stateMachine, HttpInbox inbox, List<ImportedClient> clients) {
    int registered = 0;
    for (ImportedClient client : clients) {
      var body = new LinkedHashMap<String, Object>();
      body.put("client_id", client.clientId());
      if (!client.scope().isEmpty())
        body.put("scope", client.scope());
      if (client.subject() != null)
        body.put("subject", client.subject());
      body.put("grant_types", client.grantTypes());
      if (!client.redirectUris().isEmpty())
        body.put("redirect_uris", client.redirectUris());
      HttpResponse<byte[]> response = register(stateMachine, inbox, "import", body, ImportedClients.Registrar, ImportedClients.secretHash(client));
      String name = client.clientId() + (client.name() != null ? " (" + client.name() + ")" : "");
      switch (response.statusCode()) {
        case 201 -> registered++;
        case 409 -> System.out.println("Imported client " + name + " is already registered and is kept as is");
        default -> throw new IllegalStateException(
            "Failed to import client " + name + ": " + response.statusCode() + " " + new String(response.body(), UTF_8)
        );
      }
    }
    if (!clients.isEmpty())
      System.out.println("Imported " + clients.size() + " clients, of which " + registered + " were registered now");
  }

  /// Registers a client as the registrar, outside the edge
  private static HttpResponse<byte[]> register(StateMachine stateMachine, HttpInbox inbox, String correlationId, Map<String, ?> body, String registrar, String secretHash) {
    var request = Edge.registration(
        Map.of("Content-Type", "application/json"),
        registrar,
        secretHash,
        new HttpRequest<>(Method.POST, URI.create("/clients"), Map.of(), DataType.objectMapper.writeValueAsBytes(body))
    );
    return stateMachine.onEvent(correlationId, inbox.trigger(), request)
        // First event is the triggered event, second is the response event
        .skip(1)
        .next()
        .map(event -> HttpMessageParser.parseResponse(event.data()))
        .timeout(Duration.ofSeconds(30))
        .block();
  }

}
