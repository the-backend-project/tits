package com.github.thxmasj.backend.backoffice;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpClient;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.inbox.Form;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.http.outbox.AtMostOnce;
import com.github.thxmasj.backend.http.outbox.AtMostOnceDispatcher;
import com.github.thxmasj.backend.http.outbox.DispatchContext;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.nimbusds.jwt.JWTClaimsSet;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// The backoffice as a model: every request it receives is an inbox request, and every decision a transition.
///
/// The sessions of the users are entities, `BackofficeSession`. A page requested without a session starts a sign-in
/// in a new session, which redirects the user to the provider. The provider redirects the user back with a code, which
/// the session has redeemed by an at-most-once outbox request, as a code can be redeemed only once. The outbox tells
/// the session the response, from which it verifies the ID token, decides by the claims whether the user may use the
/// backoffice, and answers the browser. Every page viewed is an event of the session, and is rendered by its
/// transition. The requests not of a session, e.g. for the static files and the health check, are entities of their
/// own, `BackofficeResource`.
///
/// Everything is stored, including the cookies, the secret of a sign-in, the code and the token response, until the
/// state machine can keep such data out of the event log (#285).
final class Sessions {

  /// The most events a session has, after which the user must sign in again. The event numbers of an entity are
  /// `SMALLINT`, so a session can't take more page views than that.
  static final int maxEvents = 30_000;
  /// How long a user has to sign in with the provider
  static final Duration signInLifetime = Duration.ofMinutes(10);
  /// The longest page returned to after signing in
  static final int maxReturnPathLength = 1000;
  static final String sessionCookie = "backoffice_session";
  static final String signInCookiePrefix = "backoffice_signin_";

  /// The path of the backoffice, under which its pages are served
  static final String Path = "/backoffice";

  enum States implements State {
    SigningIn,
    Redeeming,
    SignedIn,
    Refused,
    Failed,
    SignedOut,
    Expired
  }

  enum ResourceStates implements State {
    Served
  }

  /// A user signed in with the OpenID Connect provider, as told by the claims of the ID token
  ///
  /// @param issuer  the issuer of the ID token
  /// @param subject the `sub` of the user
  record User(String issuer, String subject, String name, String email) {

    /// The name of the user for the pages: the name, the email address or the subject, the first of them it has
    public String displayName() {
      return name != null ? name : email != null ? email : subject;
    }

  }

  /// A page requested
  ///
  /// @param path     the raw path of the page
  /// @param query    the raw query, or null
  /// @param htmx     whether htmx requests it
  /// @param fragment whether htmx requests a part of a page rather than the whole document
  record View(String path, String query, boolean htmx, boolean fragment) {

    String pathAndQuery() {
      return path + (query != null ? "?" + query : "");
    }

  }

  /// A sign-in started for a page requested without a session
  ///
  /// @param returnPath       the page to return to after signing in
  /// @param secret           the secret the PKCE verifier and the nonce are derived from, also kept in the browser
  /// @param authorizationUrl where the user signs in, with the entity id of the session as state
  record Start(String returnPath, String secret, boolean htmx, URI authorizationUrl) {}

  /// The user returning from the provider
  ///
  /// @param state  the entity id of the session, the state of the authorization request
  /// @param code   the code, if the user signed in
  /// @param error  the error, if the user didn't
  /// @param secret the secret of the sign-in from the cookie of the browser, or null if it has none
  record Callback(String state, String code, String error, String secret) {}

  /// The code to redeem, with the secret its PKCE verifier is derived from
  record Redemption(String code, String secret) {}

  /// A user signed in
  ///
  /// @param expiresAt  when the session expires, in seconds since the epoch
  /// @param returnPath the page to return to
  record SignedIn(User user, long expiresAt, String returnPath) {}

  /// A user signed in, but whose claims don't permit using the backoffice
  ///
  /// @param reason why the user is refused, e.g. the claim missing
  record Refusal(User user, String reason) {}

  /// A sign-in which failed, e.g. as the user cancelled at the provider
  ///
  /// @param status the status code of the page telling the user
  record Failure(String error, String description, int status) {}

  /// A request not of a session
  record ResourceRequest(String method, String path) {}

  /// A response of the backoffice
  record Reply(int status, Map<String, String> headers, String body) {}

  /// A page requested, which is viewed if there is a valid session, and otherwise starts a sign-in. It is never stored,
  /// as one of its outcomes always is.
  static final EventType<View, Void> ViewRequested = BasicEventType.of(
      "View requested",
      UUID.fromString("1f9c6e3b-8a2d-4d70-b4e5-9c0a7f3d5b18"),
      DataType.json(View.class),
      DataType.none()
  );
  static final EventType<Start, Start> SignInStarted = BasicEventType.of(
      "Sign-in started",
      UUID.fromString("c3f1a8e2-6b4d-4c79-8a15-2e9d7f0b5c46"),
      DataType.json(Start.class)
  );
  /// The provider can't be reached to start a sign-in
  static final EventType<Failure, Failure> SignInUnavailable = BasicEventType.of(
      "Sign-in unavailable",
      UUID.fromString("5d2a9f4c-7e1b-4c38-b6a0-3f8d2c7e1a59"),
      DataType.json(Failure.class)
  );
  /// The user returned from the provider. It is never stored, as one of its outcomes always is.
  static final EventType<Callback, Void> CallbackReceived = BasicEventType.of(
      "Callback received",
      UUID.fromString("a7e3c9d1-2f6b-4a84-9c05-6b1e8d4f2a73"),
      DataType.json(Callback.class),
      DataType.none()
  );
  static final EventType<Callback, Callback> CodeRedemptionRequested = BasicEventType.of(
      "Code redemption requested",
      UUID.fromString("e9b1d5f3-8c2a-4e67-a3d0-7f4c1b9e6d25"),
      DataType.json(Callback.class)
  );
  /// The response of the provider to the token request, as the outbox tells it. It is never stored, as one of its
  /// outcomes always is.
  static final EventType<String, Void> TokenReceived = BasicEventType.of(
      "Token received",
      UUID.fromString("3b8f6d2e-1a9c-4f75-b0e4-8d2a6c3f9e17"),
      DataType.string(),
      DataType.none()
  );
  /// The token request failed, e.g. as the provider refused the code or couldn't be reached
  static final EventType<Void, Void> TokenRequestFailed = BasicEventType.of(
      "Token request failed",
      UUID.fromString("c6a4e8b2-5d1f-4c93-8e07-1f9b3d6a2c48")
  );
  static final EventType<Failure, Failure> SignInFailed = BasicEventType.of(
      "Sign-in failed",
      UUID.fromString("e8b4f2a6-0d7c-4e39-9a51-6f3d1c8b2e94"),
      DataType.json(Failure.class)
  );
  static final EventType<SignedIn, SignedIn> UserSignedIn = BasicEventType.of(
      "User signed in",
      UUID.fromString("7e2b9d4f-3c18-4a6e-b5f0-1d8c6a2e9b73"),
      DataType.json(SignedIn.class)
  );
  static final EventType<Refusal, Refusal> SignInRefused = BasicEventType.of(
      "Sign-in refused",
      UUID.fromString("a5d0c7e3-9f21-4b84-8e6a-3c1f5b9d7e20"),
      DataType.json(Refusal.class)
  );
  static final EventType<View, View> PageViewed = BasicEventType.of(
      "Page viewed",
      UUID.fromString("6d3a8f1c-2e5b-4b96-a7c0-8e4f2d9b1a35"),
      DataType.json(View.class)
  );
  /// The session expired, as told the user requesting the page
  static final EventType<View, View> SessionExpired = BasicEventType.of(
      "Session expired",
      UUID.fromString("b2e7c4a9-5f06-4d1b-8c93-0a6e3f8d2b57"),
      DataType.json(View.class)
  );
  /// A page requested with the cookie of a session which has ended
  static final EventType<View, View> ViewRefused = BasicEventType.of(
      "View refused",
      UUID.fromString("9a4d1e7b-6c3f-4a28-b0e5-5d2c8f4a1e69"),
      DataType.json(View.class)
  );
  /// A callback of a session which isn't signing in, e.g. the user going back to it
  static final EventType<Callback, Callback> CallbackRefused = BasicEventType.of(
      "Callback refused",
      UUID.fromString("2f7c4b9e-6a3d-4e18-9d52-8b1f6e3c7a04"),
      DataType.json(Callback.class)
  );
  static final EventType<Void, Void> UserSignedOut = BasicEventType.of(
      "User signed out",
      UUID.fromString("3c8e5b2a-7d4f-4e10-9b6c-2f1a8d5e7c03")
  );
  static final EventType<ResourceRequest, ResourceRequest> ResourceRequested = BasicEventType.of(
      "Resource requested",
      UUID.fromString("4c9e2a7d-1b5f-4a63-8d0e-6f3b9a2c5e81"),
      DataType.json(ResourceRequest.class)
  );

  /// The hash of the token of the session cookie, which is the id of the session
  static final EventType<String, String> TokenClaimed = BasicEventType.of(
      "Token claimed",
      UUID.fromString("f0a6d3c9-4e2b-4f85-a1d7-8b5c2e9f6a14"),
      DataType.string()
  );
  static final IdEntityModel Tokens = IdEntityModel.of("BackofficeTokens", UUID.fromString("5e1b8d4a-9c7f-4a32-8e60-4d2f7b1c9a85"), TokenClaimed);

  static final ResponseEventType<Reply> ReplyCreated = new ResponseEventType<>(
      "Backoffice reply created",
      UUID.fromString("d7c2f9e5-1a6b-4c83-9d40-7e3b5a8c2f16"),
      DataType.json(Reply.class)
  );

  private static final Map<String, String> securityHeaders = Map.of(
      "Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      "X-Content-Type-Options", "nosniff",
      "Referrer-Policy", "same-origin"
  );
  private static final Map<String, String> staticFiles = Map.of(
      "htmx.min.js", "text/javascript;charset=UTF-8",
      "backoffice.css", "text/css;charset=UTF-8"
  );

  private final Backoffice.Config config;
  private final OpenIdConnectClient provider;
  private final Pages pages;
  private final Map<String, String> files;
  private final AtMostOnceDispatcher<Redemption, String> tokenRequests;
  /// The sessions of the users
  private final EntityModel session;
  /// A request not of a session, e.g. for a static file or the health check
  private final EntityModel resource;
  private final HttpInbox inbox;

  /// @param forwarder the HTTP client the token requests are sent with
  Sessions(Backoffice.Config config, OpenIdConnectClient provider, Pages pages, Map<String, String> files, HttpClient forwarder) {
    this.config = config;
    this.provider = provider;
    this.pages = pages;
    this.files = Map.copyOf(files);
    this.session = EntityModel.of("BackofficeSession", UUID.fromString("4b6f2c8e-1d3a-4f57-9e0b-7a2c5d8f3e61"), this::sessionTransitions);
    this.resource = EntityModel.of("BackofficeResource", UUID.fromString("8e3c1a6f-4b2d-4d97-a5e0-2f7b9c4d1e83"), this::resourceTransitions);
    AtMostOnce<String, Void> tokenRequest = HttpOutboxRequest.atMostOnce()
        .requestPayloadType(DataType.string())
        .responsePayloadType(DataType.string())
        .forwarder(forwarder)
        .inflightTimeout(Duration.ofSeconds(10))
        .processModel(session)
        .onPeerUnavailable(TokenRequestFailed)
        .onMissingResponse(TokenRequestFailed)
        .onInvalidResponseRejection(TokenRequestFailed)
        .onInvalidResponseUnknown(TokenRequestFailed)
        .contentParser(m -> valid(m.body() != null ? new String(m.body(), UTF_8) : ""))
        .onSuccess(TokenReceived, HttpResponse::body)
        .onFailure(TokenRequestFailed)
        .isAccepted(r -> r.statusCode() == 200)
        .isRejected(r -> r.statusCode() >= 400)
        .isRejectedByInvalidResponse(d -> d.t1().statusCode() >= 400)
        .build("BackofficeTokenRequest", UUID.fromString("1e5a8c3f-9b2d-4f60-a7e4-5c9b2d6f8a13"));
    this.tokenRequests = AtMostOnceDispatcher.reactive(tokenRequest, (_, r) -> provider.tokenRequest(r.code(), r.secret()));

    // The user returning from the provider, with the session as state
    HttpInboxRequest<Callback> callbackRequest = HttpInboxRequest
        .matching(m -> m.method() == Method.GET && m.uri().getPath().equals(Path + "/callback"))
        .contentParser(Sessions::callback)
        .process(CallbackReceived, session, r -> r.body(), (_, callback) -> HttpInboxRequest.entityId(callback.state()))
        .response(ReplyCreated, c -> response(c.input(), c.correlationId()))
        .build("BackofficeCallbackRequest", UUID.fromString("6b2e9c4f-3a7d-4f15-9e80-1c5d8a3f7b62"));
    // A user signing out
    HttpInboxRequest<Void> signOutRequest = HttpInboxRequest
        .matching(m -> m.method() == Method.POST && m.uri().getPath().equals(Path + "/signout"))
        .noContent()
        .process(UserSignedOut, session, (m, _) -> valid(session(m)))
        .response(ReplyCreated, c -> response(c.input(), c.correlationId()))
        .build("BackofficeSignOutRequest", UUID.fromString("e6b9d2f4-8c1a-4a53-9f70-1d4e8b3c5a27"));
    // A page requested, by the session of its cookie, or a new one if it has none
    HttpInboxRequest<View> viewRequest = HttpInboxRequest
        .matching(m -> m.method() == Method.GET && m.uri().getPath().startsWith(Path + "/") && !m.uri().getPath().startsWith(Path + "/static/"))
        .contentParser(Sessions::view)
        .process(ViewRequested, session, r -> r.body(), (m, _) -> valid(session(m)))
        .response(ReplyCreated, c -> response(c.input(), c.correlationId()))
        .build("BackofficeViewRequest", UUID.fromString("7f2d5a8e-3b6c-4e19-b4a7-0c8e1f5d9b62"));
    // Any other request, e.g. for a static file or the health check
    HttpInboxRequest<ResourceRequest> resourceRequest = HttpInboxRequest
        .matching(_ -> true)
        .contentParser(m -> valid(new ResourceRequest(m.method().name(), m.uri().getPath())))
        .process(ResourceRequested, resource, r -> r.body(), HttpInboxRequest.newEntity())
        .response(ReplyCreated, c -> response(c.input(), c.correlationId()))
        .build("BackofficeResourceRequest", UUID.fromString("9d4f1b7e-2c6a-4e83-a5b9-0e7c3f8d1a26"));
    // In the order they are matched in, as the first matching one takes a request
    this.inbox = HttpInbox.of(List.of(callbackRequest, signOutRequest, viewRequest, resourceRequest));
  }

  /// The model of the sessions
  EntityModel session() {
    return session;
  }

  /// The inbox of the backoffice's state machine, whose requests trigger events on the sessions and the other requests,
  /// which in turn trigger events on the tokens and the token requests
  HttpInbox inbox() {
    return inbox;
  }

  private Map<State, List<TransitionModel<?, ?>>> resourceTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(ResourceRequested).to(ResourceStates.Served)
                .assembleInput()
                .trigger(ReplyCreated).with(this::resource).on(Request).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
        ),
        ResourceStates.Served, List.of()
    );
  }

  private Map<State, List<TransitionModel<?, ?>>> sessionTransitions() {
    return Map.of(
        Begin, List.of(
            // A page requested without a session starts a sign-in, with the session as the state at the provider
            onEvent(ViewRequested).to(States.SigningIn)
                .assembleReactive(c -> {
                  String secret = Secrets.generate();
                  String returnPath = c.input().pathAndQuery().length() > maxReturnPathLength ? Path + "/" : c.input().pathAndQuery();
                  return provider.authorizationUrl(c.log().entityId().toString(), secret)
                      .map(url -> Optional.of(new Start(returnPath, secret, c.input().htmx(), url)))
                      .onErrorResume(e -> {
                        System.err.println("Backoffice can't reach the identity provider: " + e);
                        return Mono.just(Optional.empty());
                      });
                })
                .when(Optional::isEmpty).then(
                    onEvent(SignInUnavailable).to(States.Failed)
                        .assembleInput()
                        .trigger(ReplyCreated).with(this::failurePage).on(Request).identifiedBy(entityIdFromSession())
                        .output(Tuple2::t1),
                    _ -> new Failure("temporarily_unavailable", "The identity provider can't be reached. Please try again later.", 502)
                )
                .otherwise(
                    onEvent(SignInStarted).to(States.SigningIn)
                        .assemble(c -> tuple(c.input(), c.log().entityId()))
                        .trigger(ReplyCreated).with(d -> signInPage(d.t1(), d.t2())).on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1()),
                    Optional::get
                ),
            // Signing out without a session
            signedOut()
        ),
        States.SigningIn, List.of(
            onEvent(CallbackReceived).to(States.Redeeming)
                .assemble(c -> tuple(c.input(), c.log().one(SignInStarted)))
                .when(d -> d.t1().secret() == null || !Secrets.matches(d.t1().secret(), d.t2().secret())).then(
                    signInFailed(),
                    _ -> new Failure("invalid_request", "The sign-in has expired, or was started in another browser. Please sign in again.", 400)
                )
                .when(d -> d.t1().error() != null || d.t1().code() == null).then(
                    signInFailed(),
                    d -> new Failure(d.t1().error() != null ? d.t1().error() : "server_error", "The sign-in failed at the identity provider: " + d.t1().error(), 401)
                )
                .otherwise(
                    // The outbox tells the session the response, which then answers the browser
                    onEvent(CodeRedemptionRequested).to(States.Redeeming)
                        .assemble(c -> tuple(new Redemption(c.input().code(), c.log().one(SignInStarted).secret()), DispatchContext.of(c), c.input()))
                        .trigger(tokenRequests.requestDispatched()).withReactive(d -> tokenRequests.input(d.t2(), d.t1())).on(tokenRequests.outbox()).identifiedBy(newEntityId())
                        .output(d -> d.t1().t3()),
                    Tuple2::t1
                )
        ),
        States.Redeeming, List.of(
            onEvent(TokenReceived).to(States.SignedIn)
                .assembleReactive(c -> {
                  Start start = c.log().one(SignInStarted);
                  long now = c.timestamp().toEpochSecond();
                  return provider.verify(c.input(), start.secret())
                      .map(Optional::of)
                      .onErrorResume(e -> {
                        System.err.println("Backoffice can't verify the ID token: " + e);
                        return Mono.just(Optional.empty());
                      })
                      .map(claims -> tuple(claims, start, now));
                })
                .when(d -> d.t1().isEmpty()).then(
                    signInFailed(),
                    _ -> new Failure("server_error", "The sign-in with the identity provider failed. Please try again.", 502)
                )
                .when(d -> refusal(d.t1().get()) != null).then(
                    // Many of them may be an attempt to get in, so each is a warning
                    onEvent(SignInRefused).to(States.Refused).severity(Severity.Warning)
                        .assembleInput()
                        .trigger(ReplyCreated).with(d -> page(403, "Sign-in refused", "You are signed in as " + d.user().displayName() + ", who may not use the backoffice.", "Sign in again")).on(Request).identifiedBy(entityIdFromSession())
                        .output(Tuple2::t1),
                    d -> new Refusal(user(d.t1().get()), refusal(d.t1().get()))
                )
                .otherwise(
                    onEvent(UserSignedIn).to(States.SignedIn)
                        // The token of the session cookie, of which only the hash is the id of the session
                        .assemble(c -> tuple(c.input(), Secrets.generate(), c.entityId()))
                        .trigger(TokenClaimed).with(d -> Secrets.hash(d.t2())).on(Tokens).identifiedBy(d -> newEntityId(d.t3()))
                        .trigger(ReplyCreated).with(d -> redirect(d.t1().t1().returnPath(), sessionCookie(d.t1().t2(), config.sessionLifetime()))).on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1().t1()),
                    d -> new SignedIn(user(d.t1().get()), d.t3() + config.sessionLifetime().toSeconds(), d.t2().returnPath())
                ),
            onEvent(TokenRequestFailed).to(States.Failed)
                .assembleInput()
                .trigger(ReplyCreated).with(_ -> page(502, "Sign-in failed", "The sign-in with the identity provider failed. Please try again.", "Sign in again")).on(Request).identifiedBy(entityIdFromSession())
                .output()
        ),
        States.SignedIn, List.of(
            onEvent(ViewRequested).to(States.SignedIn)
                .assemble(c -> tuple(c.input(), c.log().one(UserSignedIn), c.timestamp().toEpochSecond(), c.log().lastEventNumber()))
                .when(d -> d.t3() >= d.t2().expiresAt() || d.t4() >= maxEvents).then(
                    onEvent(SessionExpired).to(States.Expired)
                        .assembleInput()
                        .trigger(ReplyCreated).with(Sessions::ended).on(Request).identifiedBy(entityIdFromSession())
                        .output(Tuple2::t1),
                    d -> d.t1()
                )
                .otherwise(
                    onEvent(PageViewed).to(States.SignedIn)
                        .assembleReactive(c -> {
                          User user = c.log().one(UserSignedIn).user();
                          return pages.page(c.input().path(), query(c.input().query()), user, c.input().fragment())
                              .map(page -> tuple(c.input(), page));
                        })
                        .trigger(ReplyCreated).with(d -> html(d.t2().status(), d.t2().html())).on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1()),
                    d -> d.t1()
                ),
            signedOut(),
            callbackRefused()
        ),
        States.SignedOut, ended(),
        States.Expired, ended(),
        States.Refused, List.of(callbackRefused()),
        States.Failed, List.of(callbackRefused())
    );
  }

  /// A session which has ended refuses the pages requested with its cookie, and is signed out of again
  private List<TransitionModel<?, ?>> ended() {
    return List.of(
        onEvent(ViewRequested).toSelf()
            .assembleInput()
            .otherwise(
                onEvent(ViewRefused).toSelf()
                    .assembleInput()
                    .trigger(ReplyCreated).with(Sessions::ended).on(Request).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                d -> d
            ),
        signedOut(),
        callbackRefused()
    );
  }

  private TransitionModel<Void, Void> signedOut() {
    return onEvent(UserSignedOut).to(States.SignedOut)
        .assembleInput()
        .trigger(ReplyCreated).with(_ -> withCookie(page(200, "Signed out", "You have signed out of the backoffice.", "Sign in again"), sessionCookie("", Duration.ZERO))).on(Request).identifiedBy(entityIdFromSession())
        .output();
  }

  private TransitionModel<Callback, Void> callbackRefused() {
    return onEvent(CallbackReceived).toSelf()
        .assembleInput()
        .otherwise(
            onEvent(CallbackRefused).toSelf()
                .assembleInput()
                .trigger(ReplyCreated).with(_ -> page(400, "Sign-in failed", "The sign-in has already been used. Please sign in again.", "Sign in again")).on(Request).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            d -> d
        );
  }

  private TransitionModel<Failure, Failure> signInFailed() {
    return onEvent(SignInFailed).to(States.Failed)
        .assembleInput()
        .trigger(ReplyCreated).with(this::failurePage).on(Request).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
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

  private static User user(JWTClaimsSet claims) {
    return new User(claims.getIssuer(), claims.getSubject(), string(claims, "name"), string(claims, "email"));
  }

  private static String string(JWTClaimsSet claims, String name) {
    return claims.getClaim(name) instanceof String s ? s : null;
  }

  /// The session of the cookie of a request, by the hash of its token, or a new one if it has no cookie or the cookie
  /// is of no session
  private static EntitySelector session(HttpRequest<byte[]> request) {
    String token = cookie(request.headerValue("Cookie"), sessionCookie);
    return token != null ? idEvent(TokenClaimed, Secrets.hash(token), EntitySelector.newEntity()) : EntitySelector.newEntity();
  }

  private static Validated<View> view(HttpRequest<byte[]> request) {
    boolean htmx = request.headerValue("HX-Request") != null;
    boolean fragment = htmx && request.headerValue("HX-Boosted") == null && request.headerValue("HX-History-Restore-Request") == null;
    return valid(new View(request.uri().getRawPath(), request.uri().getRawQuery(), htmx, fragment));
  }

  private static Validated<Callback> callback(HttpRequest<byte[]> request) {
    Map<String, String> query;
    try {
      query = Form.parse(request.uri().getRawQuery());
    } catch (IllegalArgumentException e) {
      return invalid("The response of the identity provider is invalid");
    }
    String state = query.get("state");
    if (Graph.uuid(state) == null)
      return invalid("The response of the identity provider is invalid");
    return valid(new Callback(state, query.get("code"), query.get("error"), cookie(request.headerValue("Cookie"), signInCookiePrefix + state)));
  }

  private static Map<String, String> query(String rawQuery) {
    try {
      return Form.parse(rawQuery);
    } catch (IllegalArgumentException e) {
      return Map.of();
    }
  }

  /// The redirect to the provider, with the secret kept in a cookie only sent to the callback. A request of htmx, e.g.
  /// for a part of a page, reloads the page instead, since htmx doesn't follow a redirect to another site.
  private Reply signInPage(Start start, UUID session) {
    if (start.htmx())
      return reload();
    return redirect(start.authorizationUrl().toString(), signInCookie(session.toString(), start.secret()));
  }

  /// A session which has ended or expired: the cookie is removed, and the page requested again, which signs the user in
  private static Reply ended(View view) {
    Reply reply = view.htmx() ? reload() : redirect(view.pathAndQuery(), null);
    return withCookie(reply, sessionCookie + "=; Path=" + Path + "; Max-Age=0; HttpOnly; SameSite=Lax");
  }

  private static Reply reload() {
    var headers = noStore();
    headers.put("HX-Refresh", "true");
    return new Reply(401, headers, "");
  }

  private Reply failurePage(Failure failure) {
    return page(failure.status(), "Sign-in failed", failure.description(), "Sign in again");
  }

  private Reply page(int status, String title, String message, String linkText) {
    return html(status, pages.message(title, message, linkText));
  }

  /// A request not of a session: a static file, the health check, a redirect to the start page, or not found
  private Reply resource(ResourceRequest request) {
    String path = request.path();
    boolean get = request.method().equals("GET");
    if (path.equals("/health"))
      return get ? text(200, "OK") : methodNotAllowed();
    if (path.equals("/") || path.equals(Path))
      return get ? redirect(Path + "/", null) : methodNotAllowed();
    if (path.startsWith(Path + "/static/")) {
      String name = path.substring((Path + "/static/").length());
      String content = files.get(name);
      if (content == null)
        return text(404, "Not Found\n");
      if (!get)
        return methodNotAllowed();
      var headers = new HashMap<>(securityHeaders);
      headers.put("Content-Type", staticFiles.get(name));
      headers.put("Cache-Control", "public, max-age=3600");
      return new Reply(200, headers, content);
    }
    return text(404, "Not Found\n");
  }

  /// The static files, read from the resources of the module
  static Map<String, String> files() {
    var files = new HashMap<String, String>();
    for (String name : staticFiles.keySet()) {
      try (var in = Sessions.class.getResourceAsStream(name)) {
        if (in == null)
          throw new IllegalStateException("The file " + name + " of the backoffice is missing");
        files.put(name, new String(in.readAllBytes(), UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }

  /// The cookie with the token of the session, sent with every page of the backoffice
  private String sessionCookie(String token, Duration maxAge) {
    return sessionCookie + "=" + token + "; Path=" + Path + "; Max-Age=" + maxAge.toSeconds() + "; HttpOnly; SameSite=Lax" + (config.secure() ? "; Secure" : "");
  }

  /// The cookie with the secret of a sign-in, only sent to the callback. It is sent with the redirect from the provider,
  /// which is a top-level navigation, with SameSite=Lax. It expires, rather than being removed, as a response can't
  /// both remove it and set the cookie of the session.
  private String signInCookie(String session, String secret) {
    return signInCookiePrefix + session + "=" + secret + "; Path=" + Path + "/callback; Max-Age=" + signInLifetime.toSeconds() + "; HttpOnly; SameSite=Lax" + (config.secure() ? "; Secure" : "");
  }

  /// The value of a cookie of a Cookie header, or null
  static String cookie(String cookies, String name) {
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

  private static Reply html(int status, String html) {
    var headers = noStore();
    headers.put("Content-Type", "text/html;charset=UTF-8");
    return new Reply(status, headers, html);
  }

  private static Reply text(int status, String text) {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Content-Type", "text/plain;charset=UTF-8");
    return new Reply(status, headers, text);
  }

  private static Reply redirect(String location, String cookie) {
    var headers = noStore();
    headers.put("Location", location);
    if (cookie != null)
      headers.put("Set-Cookie", cookie);
    return new Reply(303, headers, "");
  }

  private static Reply methodNotAllowed() {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Allow", "GET");
    return new Reply(405, headers, "");
  }

  private static Reply withCookie(Reply reply, String cookie) {
    var headers = new HashMap<>(reply.headers());
    headers.put("Set-Cookie", cookie);
    return new Reply(reply.status(), headers, reply.body());
  }

  private static HashMap<String, String> noStore() {
    var headers = new HashMap<>(securityHeaders);
    headers.put("Cache-Control", "no-store");
    return headers;
  }

  private static HttpResponse<byte[]> response(Reply reply, String correlationId) {
    var headers = new LinkedHashMap<>(reply.headers());
    headers.put("X-Correlation-Id", correlationId);
    return new HttpResponse<>(
        reply.status(),
        reasonPhrase(reply.status()),
        headers,
        reply.body().isEmpty() ? null : reply.body().getBytes(UTF_8)
    );
  }

  private static String reasonPhrase(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 303 -> "See Other";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 403 -> "Forbidden";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 502 -> "Bad Gateway";
      default -> "Status " + status;
    };
  }

}
