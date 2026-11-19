package com.github.thxmasj.backend.identityprovider.backoffice;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.backoffice.Backoffice;
import com.github.thxmasj.backend.backoffice.BackofficeApp;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.database.DataSourceBuilder;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.identityprovider.IdentityProvider;
import com.github.thxmasj.backend.identityprovider.Inbox;
import java.time.Duration;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import reactor.netty.DisposableServer;

/// Runs the backoffice against the test database the way it is deployed: the identity provider and the backoffice
/// migrated as admin, each into a schema of its own, and the backoffice served as an ordinary login, with a provider
/// which only publishes its metadata. The backoffice reads the database as the library's tests do on every database;
/// this test runs it as an app on the SQL Server of the build only.
@DisabledIfSystemProperty(named = "database", matches = "pgsql.*")
public class IdentityProviderBackofficeTest {

  private static final String applicationSchema = UUID.randomUUID().toString();
  private static final String schema = UUID.randomUUID().toString();
  private static HttpServer provider;
  private static StateMachine stateMachine;
  private static DisposableServer server;
  private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  @BeforeAll
  public static void setUp() throws Exception {
    provider = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    String issuer = "http://localhost:" + provider.getAddress().getPort();
    provider.createContext("/.well-known/openid-configuration", exchange -> {
      byte[] body = ("{\"issuer\": \"" + issuer + "\", \"authorization_endpoint\": \"" + issuer + "/authorize\", "
          + "\"token_endpoint\": \"" + issuer + "/token\", \"jwks_uri\": \"" + issuer + "/jwks\"}").getBytes(UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    provider.start();
    var admin = env("sa", "A_Str0ng_Required_Password", issuer);
    // The application is migrated into its own schema, which the backoffice is given as APPLICATION_SCHEMA
    var applicationAdmin = new HashMap<>(admin);
    applicationAdmin.put("DATABASE_SCHEMA", applicationSchema);
    IdentityProvider.migrate(com.github.thxmasj.backend.identityprovider.Config.fromEnvironment(applicationAdmin));
    BackofficeApp.migrate(BackofficeApp.Config.fromEnvironment(admin, "identity_provider", false));
    var config = BackofficeApp.Config.fromEnvironment(env("testlogin", "Please_hide_me!", issuer), "identity_provider", true);
    DataSource dataSource = new DataSourceBuilder(Database.named(config.database()), config.databaseConfig()).buildPooled();
    stateMachine = BackofficeApp.stateMachine(config, dataSource);
    server = BackofficeApp.serve("Identity provider", config, stateMachine, dataSource, Inbox.transitions(Duration.ofMinutes(5)));
  }

  @AfterAll
  public static void tearDown() throws Exception {
    server.disposeNow();
    stateMachine.close();
    provider.stop(0);
  }

  private static Map<String, String> env(String user, String password, String issuer) {
    var env = new HashMap<String, String>();
    env.put("PORT", "0");
    env.put("DATABASE_HOST", "localhost");
    env.put("DATABASE_PORT", "11433");
    env.put("DATABASE_NAME", "work");
    env.put("DATABASE_USER", user);
    env.put("DATABASE_PASSWORD", password);
    env.put("DATABASE_TRUST_SERVER_CERTIFICATE", "true");
    env.put("DATABASE_SCHEMA", schema);
    env.put("APPLICATION_SCHEMA", applicationSchema);
    env.put("DATABASE_ROLE", "Test");
    env.put("BACKOFFICE_ISSUER", issuer);
    env.put("BACKOFFICE_URL", "http://localhost:8081");
    env.put("BACKOFFICE_CLIENT_ID", "backoffice");
    env.put("BACKOFFICE_CLIENT_SECRET", "a-secret");
    env.put("BACKOFFICE_CLAIM", "roles");
    env.put("BACKOFFICE_CLAIM_VALUES", "backoffice");
    return env;
  }

  @Test
  public void whenNotSignedInThenSentToProvider() throws Exception {
    HttpResponse<String> response = get("/backoffice/");
    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue("Location").orElseThrow()).contains("/authorize?").contains("client_id=backoffice");
  }

  @Test
  public void whenStaticFileRequestedThenServed() throws Exception {
    assertThat(get("/backoffice/static/htmx.min.js").statusCode()).isEqualTo(200);
  }

  @Test
  public void whenHealthRequestedThenOk() throws Exception {
    assertThat(get("/health").statusCode()).isEqualTo(200);
    assertThat(get("/other").statusCode()).isEqualTo(404);
  }

  @Test
  public void whenMigratedAgainThenSchemaIsVerified() {
    // The migration of the backoffice can be run again, as with every deploy
    BackofficeApp.migrate(BackofficeApp.Config.fromEnvironment(env("sa", "A_Str0ng_Required_Password", "http://localhost"), "identity_provider", false));
  }

  private static HttpResponse<String> get(String path) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).GET().build(), BodyHandlers.ofString());
  }

}
