package com.github.thxmasj.backend.backoffice;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.test.Init;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxListener;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.netty.DisposableServer;

/// Runs an application with the backoffice against the test database, with users signing in with a stub provider
@Tag("database")
public class BackofficeTest {

  /// An application placing orders, each found by its order number
  enum OrderStates implements State {Placed}

  static final EntityModel Order = EntityModel.of("Order", UUID.fromString("0a3e6c1d-7b92-4f58-a4e0-2d9c5f8b1e63"));
  static final EventType<String, String> OrderPlaced = BasicEventType.of("Order placed", UUID.fromString("b6d1f8a3-2c4e-4a97-8e05-9f3b7d1c5a28"), DataType.string());
  static final EventType<String, String> OrderNumberClaimed = BasicEventType.of("Order number claimed", UUID.fromString("e2c9a5f7-4b1d-4d63-9a80-6c2e8f4b1d95"), DataType.string());
  static final IdEntityModel OrderNumbers = IdEntityModel.of("OrderNumbers", UUID.fromString("5f8b2d6e-9a3c-4e17-b0d4-1e7a3c9f5b82"), OrderNumberClaimed);
  static final HttpInboxRequest<String> PlaceOrder = HttpInboxRequest
      .matching(m -> m.method() == Method.PUT && m.uri().getPath().startsWith("/orders/"))
      .contentParser(r -> valid(r.uri().getPath().substring("/orders/".length())))
      .process(OrderPlaced, Order, r -> r.body(), newEntity())
      .build("PlaceOrder", UUID.fromString("9c4a7e2f-1d5b-4b08-8f63-3a6d0e9c2b74"));

  private static final String schema = UUID.randomUUID().toString();
  private static final ProviderStub provider = provider();
  private static final ProviderStub.User ada = new ProviderStub.User("user-ada", "ada@example.com", "Ada Lovelace", List.of("backoffice"));
  private static final ProviderStub.User bob = new ProviderStub.User("user-bob", "bob@example.com", "Bob", List.of("sales"));
  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;
  private static StateMachine stateMachine;
  private static DisposableServer server;
  private static DisposableServer shortLived;
  private static DisposableServer disabled;
  private static final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
  /// The tokens of the sessions, to check that they don't enter the event log
  private static final List<String> tokens = new ArrayList<>();

  @BeforeAll
  static void setUp() {
    var application = new HashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    application.put(Order, Map.of(
        Begin, List.of(
            onEvent(OrderPlaced).to(OrderStates.Placed)
                .assemble(c -> tuple(c.input(), c.entityId(), c.eventReference()))
                .trigger(OrderNumberClaimed).with(d -> d.t1()).on(OrderNumbers).identifiedBy(d -> newEntityId(d.t2()))
                .trigger(RequestCompleted).with(d -> tuple("Placed", d.t1().t3())).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1())
        ),
        OrderStates.Placed, List.of()
    ));
    application.put(OrderNumbers, OrderNumbers.transitions());
    application.putAll(Backoffice.transitions());
    var requests = new ArrayList<HttpInboxRequest<?>>(List.of(PlaceOrder));
    requests.addAll(Backoffice.requests());
    transitions = HttpInbox.transitions(requests, application);
    stateMachine = Init.stateMachine(schema, transitions);
    server = listen(config(Duration.ofHours(1)));
    shortLived = listen(config(Duration.ofSeconds(1)));
    disabled = new HttpInboxListener(stateMachine).filter(Backoffice.none()).listen(0);
  }

  @AfterAll
  static void tearDown() throws Exception {
    server.disposeNow();
    shortLived.disposeNow();
    disabled.disposeNow();
    provider.close();
    stateMachine.close();
  }

  private static DisposableServer listen(Backoffice.Config config) {
    // The URL is known only once the server listens, so the backoffice is told it by a filter in front
    var backoffice = new Backoffice[1];
    DisposableServer listening = new HttpInboxListener(stateMachine)
        .filter((request, next) -> backoffice[0].filter(request, next))
        .listen(0);
    backoffice[0] = new Backoffice("Orders", withUrl(config, "http://localhost:" + listening.port()), Init.client(), schema, transitions);
    return listening;
  }

  private static Backoffice.Config config(Duration sessionLifetime) {
    return new Backoffice.Config(
        URI.create("http://localhost"),
        URI.create(provider.issuer()),
        ProviderStub.clientId,
        ProviderStub.clientSecret,
        "openid email profile",
        "roles",
        Set.of("backoffice", "support"),
        sessionLifetime
    );
  }

  private static Backoffice.Config withUrl(Backoffice.Config c, String url) {
    return new Backoffice.Config(URI.create(url), c.issuer(), c.clientId(), c.clientSecret(), c.scope(), c.claim(), c.claimValues(), c.sessionLifetime());
  }

  @Test
  void pageWithoutSessionSendsUserToProvider() throws Exception {
    var response = get(server, "/backoffice/entities?x=1", null);
    assertThat(response.statusCode()).isEqualTo(303);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location).startsWith(provider.issuer() + "/authorize?");
    assertThat(location).contains("code_challenge_method=S256", "client_id=backoffice", "redirect_uri=" + encoded(url(server) + "/backoffice/callback"));
    assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).startsWith("backoffice_signin_").contains("HttpOnly", "Path=/backoffice/callback");
  }

  @Test
  void userSignsInAndReturnsToPage() throws Exception {
    provider.signInAs(ada);
    String path = "/backoffice/models/" + Order.id();
    var signedIn = signIn(server, path);
    assertThat(signedIn.statusCode()).isEqualTo(303);
    assertThat(signedIn.headers().firstValue("Location")).hasValue(path);
    String cookie = sessionCookie(signedIn);
    var page = get(server, path, cookie);
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body()).contains("<h1>Order</h1>", "Ada Lovelace", "htmx.min.js");
    // Named after the service, in the header and in the title with the page
    assertThat(page.body()).contains("<a class=\"home\" href=\"/backoffice/\">Orders backoffice</a>", "<title>Order – Orders backoffice</title>");
    // Nothing is loaded from other sites
    assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp -> assertThat(csp)
        .contains("default-src 'self'")
        .doesNotContain("img-src", "script-src", "style-src", "*", "http"));
  }

  @Test
  void userWithoutPermittedClaimIsRefused() throws Exception {
    provider.signInAs(bob);
    var refused = signIn(server, "/backoffice/");
    assertThat(refused.statusCode()).isEqualTo(403);
    assertThat(refused.body()).contains("Bob", "may not use the backoffice");
    assertThat(refused.headers().firstValue("Set-Cookie")).isEmpty();
    assertThat(sessionEvents("Sign-in refused")).isTrue();
  }

  @Test
  void failedSignInIsShown() throws Exception {
    provider.failWith("access_denied");
    var failed = signIn(server, "/backoffice/");
    assertThat(failed.statusCode()).isEqualTo(401);
    assertThat(failed.body()).contains("access_denied");
  }

  @Test
  void callbackWithoutCookieOfSignInIsRefused() throws Exception {
    provider.signInAs(ada);
    var start = get(server, "/backoffice/", null);
    var redirect = http.send(HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow())).build(), BodyHandlers.ofString());
    var callback = get(server, URI.create(redirect.headers().firstValue("Location").orElseThrow()).getRawPath() + "?" + URI.create(redirect.headers().firstValue("Location").orElseThrow()).getRawQuery(), null);
    assertThat(callback.statusCode()).isEqualTo(400);
    assertThat(callback.body()).contains("started in another browser");
  }

  @Test
  void entityIsFoundByIdAndShowsItsGraph() throws Exception {
    String orderNumber = "A-" + UUID.randomUUID();
    var placed = http.send(HttpRequest.newBuilder(URI.create(url(server) + "/orders/" + orderNumber)).PUT(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    assertThat(placed.statusCode()).isEqualTo(201);
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(server, "/backoffice/"));

    var search = get(server, "/backoffice/search?q=" + orderNumber, cookie, Map.of("HX-Request", "true"));
    assertThat(search.statusCode()).isEqualTo(200);
    assertThat(search.body()).doesNotContain("<html").contains("Order number claimed");
    String orderId = firstEntityId(search.body());

    var entity = get(server, "/backoffice/entities/" + orderId, cookie);
    assertThat(entity.statusCode()).isEqualTo(200);
    assertThat(entity.body()).contains("<h1>Order <code>" + orderId + "</code></h1>", "Order placed", orderNumber, "Order number claimed");
    // The inbox request of the order refers to it by the index of the inbox
    assertThat(entity.body()).contains("Referred to by", "PlaceOrder", "Process referenced");
    String requestId = entityIdAfter(entity.body(), "PlaceOrder <code>");

    // The inbox request, expanded in place, refers back to the order by its data
    var request = get(server, "/backoffice/entities/" + requestId, cookie, Map.of("HX-Request", "true"));
    assertThat(request.body()).doesNotContain("<html").contains("Request accepted", "PUT /orders/" + orderNumber, "Refers to", "<a href=\"/backoffice/entities/" + orderId + "\">");

    var unknown = get(server, "/backoffice/entities/" + UUID.randomUUID(), cookie);
    assertThat(unknown.statusCode()).isEqualTo(404);
  }

  @Test
  void viewsAreRecordedOnSession() throws Exception {
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(server, "/backoffice/"));
    String path = "/backoffice/search?q=" + UUID.randomUUID();
    assertThat(get(server, path, cookie).statusCode()).isEqualTo(200);
    var sessions = get(server, "/backoffice/models/" + Backoffice.sessionModel().id(), cookie);
    String sessionId = firstEntityId(sessions.body());
    var session = get(server, "/backoffice/entities/" + sessionId, cookie);
    assertThat(session.body()).contains("User signed in", "Ada Lovelace", "Page viewed", path, "Token claimed");
  }

  @Test
  void signedOutSessionIsNoLongerLetIn() throws Exception {
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(server, "/backoffice/"));
    assertThat(get(server, "/backoffice/", cookie).statusCode()).isEqualTo(200);
    var signedOut = http.send(request(server, "/backoffice/signout", cookie, Map.of()).POST(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    assertThat(signedOut.statusCode()).isEqualTo(200);
    assertThat(signedOut.body()).contains("You have signed out");
    assertThat(signedOut.headers().firstValue("Set-Cookie").orElseThrow()).startsWith("backoffice_session=;").contains("Max-Age=0");
    var again = get(server, "/backoffice/", cookie);
    assertThat(again.statusCode()).isEqualTo(303);
    assertThat(again.headers().firstValue("Location").orElseThrow()).startsWith(provider.issuer());
  }

  @Test
  void expiredSessionSignsInAgain() throws Exception {
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(shortLived, "/backoffice/"));
    Thread.sleep(2000);
    var expired = get(shortLived, "/backoffice/", cookie);
    assertThat(expired.statusCode()).isEqualTo(303);
    assertThat(expired.headers().firstValue("Location").orElseThrow()).startsWith(provider.issuer());
    // A request of htmx reloads the page instead, as htmx doesn't follow redirects to the provider
    var fragment = get(shortLived, "/backoffice/search?q=x", cookie, Map.of("HX-Request", "true"));
    assertThat(fragment.statusCode()).isEqualTo(401);
    assertThat(fragment.headers().firstValue("HX-Refresh")).hasValue("true");
    assertThat(sessionEvents("Session expired")).isTrue();
  }

  @Test
  void staticFilesAreServed() throws Exception {
    var htmx = get(server, "/backoffice/static/htmx.min.js", null);
    assertThat(htmx.statusCode()).isEqualTo(200);
    assertThat(htmx.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/javascript");
    assertThat(htmx.body()).contains("htmx");
    assertThat(get(server, "/backoffice/static/backoffice.css", null).statusCode()).isEqualTo(200);
    assertThat(get(server, "/backoffice/static/other.js", null).statusCode()).isEqualTo(404);
  }

  @Test
  void backofficeWithoutConfigurationIsNotFound() throws Exception {
    assertThat(get(disabled, "/backoffice/", null).statusCode()).isEqualTo(404);
    // Other requests reach the application
    var placed = http.send(HttpRequest.newBuilder(URI.create(url(disabled) + "/orders/B-" + UUID.randomUUID())).PUT(BodyPublishers.noBody()).build(), BodyHandlers.ofString());
    assertThat(placed.statusCode()).isEqualTo(201);
  }

  @Test
  void trustedHeadersAreNotTakenFromClients() throws Exception {
    var response = get(server, "/backoffice/", null, Map.of("X-Backoffice-Token-Hash", "forged"));
    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue("Location").orElseThrow()).startsWith(provider.issuer());
  }

  @Test
  void credentialsDoNotEnterEventLog() throws Exception {
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(server, "/backoffice/"));
    assertThat(get(server, "/backoffice/", cookie).statusCode()).isEqualTo(200);
    List<byte[]> data = Init.client().sql("SELECT Data FROM \"" + schema + "\".\"Event\" WHERE Data IS NOT NULL")
        .name("Test.AllData")
        .map(row -> row.get("Data", byte[].class))
        .all()
        .collectList()
        .block();
    var secrets = new ArrayList<String>(provider.issued);
    secrets.addAll(tokens);
    for (byte[] d : data) {
      String text = new String(d, UTF_8);
      for (String secret : secrets)
        assertThat(text).doesNotContain(secret);
    }
  }

  /// Signs in through the provider, starting at the path, and gives the response of the callback
  private static HttpResponse<String> signIn(DisposableServer server, String path) throws Exception {
    var start = get(server, path, null);
    assertThat(start.statusCode()).isEqualTo(303);
    String signInCookie = start.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    var authorized = http.send(HttpRequest.newBuilder(URI.create(start.headers().firstValue("Location").orElseThrow())).build(), BodyHandlers.ofString());
    assertThat(authorized.statusCode()).isEqualTo(302);
    URI callback = URI.create(authorized.headers().firstValue("Location").orElseThrow());
    assertThat(callback.getPath()).isEqualTo("/backoffice/callback");
    return get(server, callback.getRawPath() + "?" + callback.getRawQuery(), signInCookie);
  }

  private static String sessionCookie(HttpResponse<String> signedIn) {
    String cookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
    assertThat(cookie).startsWith("backoffice_session=").contains("HttpOnly", "SameSite=Lax", "Path=/backoffice");
    String value = cookie.split(";")[0];
    tokens.add(value.substring(value.indexOf('=') + 1));
    return value;
  }

  private static boolean sessionEvents(String eventName) throws Exception {
    // Read with a session of a permitted user
    provider.signInAs(ada);
    String cookie = sessionCookie(signIn(server, "/backoffice/"));
    var sessions = get(server, "/backoffice/models/" + Backoffice.sessionModel().id(), cookie).body();
    Matcher matcher = Pattern.compile("/backoffice/entities/([0-9a-f-]{36})").matcher(sessions);
    while (matcher.find()) {
      if (get(server, "/backoffice/entities/" + matcher.group(1), cookie).body().contains(eventName))
        return true;
    }
    return false;
  }

  private static String firstEntityId(String html) {
    Matcher matcher = Pattern.compile("/backoffice/entities/([0-9a-f-]{36})").matcher(html);
    assertThat(matcher.find()).as("An entity in %s", html).isTrue();
    return matcher.group(1);
  }

  private static String entityIdAfter(String html, String prefix) {
    Matcher matcher = Pattern.compile(Pattern.quote(prefix) + "([0-9a-f-]{36})").matcher(html);
    assertThat(matcher.find()).as("%s in %s", prefix, html).isTrue();
    return matcher.group(1);
  }

  private static HttpResponse<String> get(DisposableServer server, String path, String cookie) throws Exception {
    return get(server, path, cookie, Map.of());
  }

  private static HttpResponse<String> get(DisposableServer server, String path, String cookie, Map<String, String> headers) throws Exception {
    return http.send(request(server, path, cookie, headers).GET().build(), BodyHandlers.ofString());
  }

  private static HttpRequest.Builder request(DisposableServer server, String path, String cookie, Map<String, String> headers) {
    var request = HttpRequest.newBuilder(URI.create(url(server) + path));
    if (cookie != null)
      request.header("Cookie", cookie);
    headers.forEach(request::header);
    return request;
  }

  private static String url(DisposableServer server) {
    return "http://localhost:" + server.port();
  }

  private static String encoded(String s) {
    return java.net.URLEncoder.encode(s, UTF_8);
  }

  private static ProviderStub provider() {
    try {
      return new ProviderStub();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
