package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.identityprovider.Clients.Client;
import static com.github.thxmasj.backend.identityprovider.Clients.ClientIdClaimed;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.Secrets;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import com.github.thxmasj.backend.identityprovider.Inbox.Redirect;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import reactor.core.publisher.Mono;

/// Sign-in with the OpenID Connect authorization code flow. Every authorization request is an entity of its own, which
/// records the request of the client, the user who signed in, and the redemption of the code issued to the client.
///
/// Users sign in with an upstream OpenID Connect provider, to which they are redirected when the request is started
/// here. The entity id of the authorization is the `state` given the upstream provider, so the user returning from it
/// is routed to the authorization. The user returns to the callback, where an [#upstreamSignIn(OpenIdConnectClient)],
/// an entity of the session only, redeems the code of the upstream provider, so its code and tokens never enter the
/// event log, and passes the user it signed in on to the authorization. The code issued to the client is generated
/// for the request, and only its hash enters the event log, as an id of the authorization, so the token endpoint finds
/// the authorization by it. The code itself is only in the redirect to the client, which the event log masks.
public interface Authorizations {

  /// How long an authorization code is valid, RFC 6749 section 4.1.2
  Duration CodeLifetime = Duration.ofSeconds(60);

  /// The scopes of OpenID Connect a client may request. Other scopes are ignored, OpenID Connect Core section 3.1.2.1.
  List<String> SupportedScopes = List.of("openid", "email", "profile");

  EntityModel Authorization = EntityModel.of("Authorization", UUID.fromString("7ff5bfea-81ed-4e10-aa73-e80e69884814"), Authorizations::authorizationTransitions);

  enum States implements State {
    Authenticating,
    CodeIssued,
    Redeemed,
    Refused,
    Failed
  }

  /// An authorization request of RFC 6749 section 4.1.1, as parsed from the query of the request. A request with a
  /// problem which the client is to be told about by a redirect has the error of RFC 6749 section 4.1.2.1.
  ///
  /// @param scope         the requested scope, or null
  /// @param codeChallenge the PKCE challenge of RFC 7636, with the method S256, or null
  record Request(
      String clientId,
      String redirectUri,
      String scope,
      String state,
      String nonce,
      String codeChallenge,
      String error,
      String errorDescription
  ) {}

  /// An authorization request with a valid client and redirect URI, for which the user is to sign in
  ///
  /// @param scope the scope granted: the scopes of OpenID Connect requested
  record Started(String clientId, String redirectUri, String scope, String state, String nonce, String codeChallenge) {}

  /// An authorization request refused or failed
  ///
  /// @param error       an error code of RFC 6749 section 4.1.2.1
  /// @param redirectUri where the client is told about the error, or null if it can't be redirected to, as the client
  ///                    or the redirect URI is invalid
  record Refusal(String clientId, String error, String description, String redirectUri, String state) {}

  /// A user signed in with the upstream provider
  ///
  /// @param subject  the `sub` of the user at the upstream provider
  /// @param authTime when the user signed in, in seconds since the epoch, or null if the upstream provider did not tell
  record User(String subject, String email, Boolean emailVerified, String name, Long authTime) {}

  /// The user signed in, and the code generated for the client, of which only the hash is stored
  record SignIn(User user, String codeHash, String code) {}

  /// @param authTime when the user signed in, in seconds since the epoch
  /// @param issuedAt when the code was issued, in seconds since the epoch
  record Authenticated(User user, String codeHash, long authTime, long issuedAt) {}

  /// The user failed to sign in with the upstream provider, e.g. by cancelling
  ///
  /// @param error an error code of RFC 6749 section 4.1.2.1, which the client is redirected with
  record Failure(String error, String description) {}

  /// The code redeemed by a token request
  ///
  /// @param tokenRequest the entity id of the token request
  record Redemption(String tokenRequest, String clientId) {}

  /// What a redeemed code was issued for
  record Granted(Started request, Authenticated authentication) {}

  EventType<Request, Void> AuthorizationRequested = BasicEventType.of(
      "Authorization requested",
      UUID.fromString("517ed1d7-3700-4db5-a71d-328e18fe2702"),
      DataType.json(Request.class),
      DataType.none()
  );
  EventType<Started, Started> AuthorizationStarted = BasicEventType.of(
      "Authorization started",
      UUID.fromString("a9a648d7-8701-453f-8d1c-7093a269ac85"),
      DataType.json(Started.class)
  );
  EventType<Refusal, Refusal> AuthorizationRefused = BasicEventType.of(
      "Authorization refused",
      UUID.fromString("cade7e8d-d230-4e92-adc6-67cf9fa03fda"),
      DataType.json(Refusal.class)
  );
  EventType<Refusal, Refusal> AuthorizationErrorReturned = BasicEventType.of(
      "Authorization error returned",
      UUID.fromString("f8c88b2c-e9c9-4067-98a0-7ff11b000568"),
      DataType.json(Refusal.class)
  );
  EventType<SignIn, Authenticated> UserAuthenticated = BasicEventType.of(
      "User authenticated",
      UUID.fromString("b323f8e3-faa4-46f7-aa1b-1d5501fdc28c"),
      DataType.json(SignIn.class),
      DataType.json(Authenticated.class)
  );
  EventType<Failure, Failure> UserAuthenticationFailed = BasicEventType.of(
      "User authentication failed",
      UUID.fromString("89b7566c-0f9b-4133-8560-7a34508e55ee"),
      DataType.json(Failure.class)
  );
  EventType<Redemption, Granted> CodeRedeemed = BasicEventType.of(
      "Code redeemed",
      UUID.fromString("f1566478-42a9-4a09-8f10-3e4319826bad"),
      DataType.json(Redemption.class),
      DataType.json(Granted.class)
  );

  /// The hash of the code issued for an authorization, which is its id
  EventType<String, String> CodeClaimed = BasicEventType.of(
      "Code claimed",
      UUID.fromString("914c9ed3-4262-4206-8f26-5afad478d267"),
      DataType.string()
  );
  IdEntityModel Codes = IdEntityModel.of("Codes", UUID.fromString("a5b6d5e7-e5e9-4b16-9d7b-bd08c596bdd0"), CodeClaimed);

  private static Map<State, List<TransitionModel<?, ?>>> authorizationTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(AuthorizationRequested).to(States.Authenticating)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(Clients.ClientLookedUp).on(Client).identifiedBy(d -> idEvent(ClientIdClaimed, d.t1().clientId()))
                // An invalid client or redirect URI is not redirected to, RFC 6749 section 4.1.2.1. isRejected()
                // marks a rejected read as handled.
                .when(d -> d.t2().isRejected() || !d.t2().isAccepted()).then(
                    refuse(),
                    d -> new Refusal(d.t1().t1().clientId(), "invalid_request", "The client is unknown", null, null)
                )
                .when(d -> !registration(d.t2()).redirectUris().contains(d.t1().t1().redirectUri())).then(
                    refuse(),
                    d -> new Refusal(d.t1().t1().clientId(), "invalid_request", "The redirect URI is not registered for the client", null, null)
                )
                .when(d -> d.t1().t1().error() != null).then(
                    returnError(),
                    d -> refusal(d.t1().t1(), d.t1().t1().error(), d.t1().t1().errorDescription())
                )
                .when(d -> !registration(d.t2()).grantTypes().contains(Clients.AuthorizationCode)).then(
                    returnError(),
                    d -> refusal(d.t1().t1(), "unauthorized_client", "The client may not use the authorization code grant")
                )
                .otherwise(
                    onEvent(AuthorizationStarted).to(States.Authenticating)
                        .assemble(c -> tuple(c.input(), c.log().entityId()))
                        // The response creator redirects the user to the upstream provider, with the authorization as state
                        .trigger(Inbox.ReplyCreated).with(d -> Reply.json(200, Map.of("authorization", d.t2().toString()))).on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1()),
                    d -> started(d.t1().t1())
                )
        ),
        States.Authenticating, List.of(
            onEvent(UserAuthenticated).to(States.CodeIssued)
                .assemble(c -> tuple(authenticated(c.input(), c.timestamp().toEpochSecond()), c.log().one(AuthorizationStarted), c.entityId(), c.input().code()))
                .trigger(CodeClaimed).with(d -> d.t1().codeHash()).on(Codes).identifiedBy(d -> newEntityId(d.t3()))
                // The code is in the reply, which is not stored, and the redirect, which the event log masks
                .trigger(Inbox.ReplyCreated).with(d -> Reply.json(302, redirect(d.t1().t2().redirectUri(), d.t1().t2().state(), Map.of("code", d.t1().t4())))).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1()),
            onEvent(UserAuthenticationFailed).to(States.Failed).severity(Severity.Warning)
                .assemble(c -> tuple(c.input(), c.log().one(AuthorizationStarted)))
                .trigger(Inbox.ReplyCreated).with(d -> Reply.json(302, error(d.t2().redirectUri(), d.t2().state(), d.t1().error(), d.t1().description()))).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1())
        ),
        // A code can be redeemed once, whether or not the token request redeeming it succeeds
        States.CodeIssued, List.of(
            onEvent(CodeRedeemed).to(States.Redeemed)
                .assemble((_, log) -> new Granted(log.one(AuthorizationStarted), log.one(UserAuthenticated)))
                .output(d -> d)
        ),
        States.Redeemed, List.of(),
        States.Refused, List.of(),
        States.Failed, List.of()
    );
  }

  /// A request with an invalid client or redirect URI, which is answered with an error page. As many of them may be
  /// an attempt to find the clients, each is a warning.
  private static TransitionModel<Refusal, Refusal> refuse() {
    return onEvent(AuthorizationRefused).to(States.Refused).severity(Severity.Warning)
        .assembleInput()
        .trigger(Inbox.ReplyCreated).with(d -> Reply.error(400, d.error(), d.description())).on(Request).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  /// A request the client is told about by a redirect with the error
  private static TransitionModel<Refusal, Refusal> returnError() {
    return onEvent(AuthorizationErrorReturned).to(States.Refused)
        .assembleInput()
        .trigger(Inbox.ReplyCreated).with(d -> Reply.json(302, error(d.redirectUri(), d.state(), d.error(), d.description()))).on(Request).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  private static Refusal refusal(Request request, String error, String description) {
    return new Refusal(request.clientId(), error, description, request.redirectUri(), request.state());
  }

  private static Registration registration(ProcessResult<Registration> result) {
    return result.accepted().event().getUnmarshalledData();
  }

  /// The request with the scope granted: the scopes of OpenID Connect requested, in the order requested
  private static Started started(Request request) {
    List<String> scopes = Scopes.parse(request.scope()).stream().filter(SupportedScopes::contains).toList();
    return new Started(request.clientId(), request.redirectUri(), Scopes.format(scopes), request.state(), request.nonce(), request.codeChallenge());
  }

  private static Authenticated authenticated(SignIn signIn, long now) {
    long authTime = signIn.user().authTime() != null ? signIn.user().authTime() : now;
    return new Authenticated(signIn.user(), signIn.codeHash(), authTime, now);
  }

  private static Redirect error(String redirectUri, String state, String error, String description) {
    var parameters = new LinkedHashMap<String, String>();
    parameters.put("error", error);
    if (description != null)
      parameters.put("error_description", description);
    return redirect(redirectUri, state, parameters);
  }

  /// A redirect to the client with the parameters and the state of its request, if it had one
  private static Redirect redirect(String redirectUri, String state, Map<String, String> parameters) {
    var all = new LinkedHashMap<>(parameters);
    if (state != null)
      all.put("state", state);
    return new Redirect(redirectUri, all);
  }

  /// The errors of an upstream provider passed on to the client, RFC 6749 section 4.1.2.1 and OpenID Connect Core
  /// section 3.1.2.6. Others are passed on as `server_error`.
  Set<String> upstreamErrors = Set.of(
      "access_denied", "temporarily_unavailable", "interaction_required", "login_required", "account_selection_required", "consent_required"
  );

  /// The user returning to the callback from the upstream provider
  ///
  /// @param authorization the entity id of the authorization, the `state` of the upstream provider
  /// @param upstreamCode  the code of the upstream provider, or null if the user didn't sign in
  /// @param upstreamError the error of the upstream provider, or null
  /// @param secret        the secret of the sign-in, from the cookie of the browser it was started in
  /// @param code          the code generated for the client
  record Callback(UUID authorization, String upstreamCode, String upstreamError, String secret, String code) {}

  /// What the upstream provider said: that the user signed in, that the user didn't, or neither, if its response was
  /// invalid
  record Outcome(UUID authorization, SignIn signIn, Failure failure) {}

  enum SignInStates implements State {Redeemed}

  EventType<Callback, Void> CallbackReceived = BasicEventType.of(
      "Callback received",
      UUID.fromString("4e1b7c9a-2d5f-4a83-b6e0-9c3f1a7d5e28"),
      DataType.json(Callback.class),
      DataType.none()
  );
  EventType<Outcome, Void> UpstreamUserSignedIn = BasicEventType.of(
      "Upstream user signed in",
      UUID.fromString("b8d2f6a4-1e9c-4b57-8a3d-5f0c7e2b9a16"),
      DataType.json(Outcome.class),
      DataType.none()
  );
  EventType<Outcome, Void> UpstreamSignInFailed = BasicEventType.of(
      "Upstream sign-in failed",
      UUID.fromString("6c3a9e1f-7b4d-4c20-9f5e-2a8d6b1c4e73"),
      DataType.json(Outcome.class),
      DataType.none()
  );
  EventType<Void, Void> SignInPassedOn = BasicEventType.of("Sign-in passed on", UUID.fromString("d5f1a8c3-9e2b-4d76-a4c0-7b3e1f9d2a58"));
  /// The sign-in can't be passed on, as the response of the upstream provider is invalid, or the authorization is
  /// unknown or no longer waits for the user
  EventType<Void, Void> SignInUnanswerable = BasicEventType.of("Sign-in unanswerable", UUID.fromString("2a7e4c1b-8f3d-4e95-b6a2-0d9c5f7e1b34"));

  /// The sign-ins with the upstream provider at the callback, which are entities of the session only, so that the code
  /// and the tokens of the upstream provider, and the secret of the sign-in, never enter the event log. Only the user
  /// signed in, or the failure, is passed on to the authorization.
  static EntityModel upstreamSignIn(OpenIdConnectClient upstream) {
    return EntityModel.notStored("Upstream sign-in", UUID.fromString("9f2d6b8e-3a1c-4e57-b9d4-6c0a2e8f1b73"), () -> signInTransitions(upstream));
  }

  private static Map<State, List<TransitionModel<?, ?>>> signInTransitions(OpenIdConnectClient upstream) {
    return Map.of(
        Begin, List.of(
            onEvent(CallbackReceived).to(SignInStates.Redeemed)
                .assembleReactive(c -> outcome(upstream, c.input()))
                .when(o -> o.signIn() != null).then(passOn(UpstreamUserSignedIn, UserAuthenticated, Outcome::signIn), o -> o)
                .when(o -> o.failure() != null).then(passOn(UpstreamSignInFailed, UserAuthenticationFailed, Outcome::failure), o -> o)
                .otherwise(unanswerable(), _ -> null)
        ),
        SignInStates.Redeemed, List.of()
    );
  }

  /// Passes the outcome on to the authorization, which answers the user. One it can't be passed on to is answered with
  /// an error page.
  private static <T> TransitionModel<Outcome, Void> passOn(EventType<Outcome, Void> type, EventType<T, ?> event, java.util.function.Function<Outcome, T> data) {
    return onEvent(type).to(SignInStates.Redeemed)
        .assembleInput()
        .trigger(event).with(data).on(Authorization).identifiedBy(o -> EntitySelector.entityId(o.authorization()))
        // isRejected() marks a rejected event as handled
        .when(d -> d.t2().isRejected() || !d.t2().isAccepted()).then(unanswerable(), _ -> null)
        .otherwise(onEvent(SignInPassedOn).to(SignInStates.Redeemed).output(), _ -> null);
  }

  private static TransitionModel<Void, Void> unanswerable() {
    return onEvent(SignInUnanswerable).to(SignInStates.Redeemed)
        .trigger(Inbox.ReplyCreated).with(_ -> new Reply(400, "{}")).on(Request).identifiedBy(entityIdFromSession())
        .output();
  }

  /// Redeems the code of the upstream provider, unless it returned an error
  private static Mono<Outcome> outcome(OpenIdConnectClient upstream, Callback callback) {
    UUID authorization = callback.authorization();
    if (callback.upstreamError() != null || callback.upstreamCode() == null) {
      // The error of the upstream provider is passed on only if it is one of the known ones, which are short
      String error = callback.upstreamError() != null && upstreamErrors.contains(callback.upstreamError()) ? callback.upstreamError() : "server_error";
      return Mono.just(new Outcome(authorization, null, new Failure(error, "The sign-in failed at the identity provider: " + error)));
    }
    return upstream.redeem(callback.upstreamCode(), callback.secret())
        .map(Authorizations::user)
        .map(user -> user.subject() == null ?
            new Outcome(authorization, null, null) :
            new Outcome(authorization, new SignIn(user, Secrets.hash(callback.code()), callback.code()), null))
        .onErrorResume(e -> {
          System.err.println("Sign-in with the upstream provider failed for authorization " + authorization + ": " + e);
          return Mono.just(new Outcome(authorization, null, new Failure("server_error", "The sign-in with the identity provider failed")));
        });
  }

  /// The user of the claims of the upstream ID token. The upstream provider's code and tokens never enter the event
  /// log, only these claims.
  private static User user(JWTClaimsSet claims) {
    try {
      return new User(
          claims.getSubject(),
          claims.getStringClaim("email"),
          claims.getBooleanClaim("email_verified"),
          claims.getStringClaim("name"),
          claims.getClaim("auth_time") instanceof Number authTime ? authTime.longValue() : null
      );
    } catch (ParseException e) {
      throw new IllegalStateException("The upstream ID token has an invalid claim: " + e.getMessage(), e);
    }
  }

}
