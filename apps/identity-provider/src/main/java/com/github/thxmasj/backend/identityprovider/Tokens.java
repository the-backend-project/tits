package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.identityprovider.Clients.Client;
import static com.github.thxmasj.backend.identityprovider.Clients.ClientIdClaimed;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.identityprovider.Authorizations.Granted;
import com.github.thxmasj.backend.identityprovider.Authorizations.Redemption;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/// Access tokens for the client credentials grant of RFC 6749 section 4.4, and access tokens and ID tokens for the
/// authorization code grant of section 4.1, with which users sign in to clients by OpenID Connect. Every token request
/// is an entity of its own, which records the tokens issued or why they were refused. Its entity id is the `jti` of
/// the access token. The claims of the user a token was issued for are read from its token request by the UserInfo
/// endpoint.
public interface Tokens {

  EntityModel TokenRequest = EntityModel.of("Token request", UUID.fromString("4f8c2a6e-9d13-4b70-8e5f-3a1d7c9b2e48"));

  enum States implements State {
    Issued,
    UserTokensIssued,
    Refused
  }

  /// A token request of a client which is yet to be authenticated
  ///
  /// @param scope the requested scope, or null for all the scopes of the client
  record Request(String clientId, String secretHash, String scope) {}

  /// @param subject   the `sub` of the token: the subject of the client, or its client id if it has none
  /// @param issuedAt  in seconds since the epoch
  /// @param expiresAt in seconds since the epoch
  record IssuedToken(String id, String clientId, String subject, String scope, long issuedAt, long expiresAt) {}

  /// @param error an error code of RFC 6749 section 5.2
  record Refusal(String clientId, String error, String description) {}

  /// A token request with an authorization code of a client which is yet to be authenticated
  ///
  /// @param codeHash      the hash of the code
  /// @param codeChallenge the S256 PKCE challenge of the code verifier, or null if the request had none
  record CodeRequest(String clientId, String secretHash, String codeHash, String redirectUri, String codeChallenge) {}

  /// The claims of an ID token, OpenID Connect Core section 2, and of the UserInfo response. The issuer is added when
  /// it is signed. The email and name are those of the user if the scopes `email` and `profile` were granted, and
  /// otherwise null.
  ///
  /// @param audience the client id of the client
  /// @param authTime when the user signed in, in seconds since the epoch
  record IdToken(
      String subject,
      String audience,
      String nonce,
      long authTime,
      long issuedAt,
      long expiresAt,
      String email,
      Boolean emailVerified,
      String name
  ) {}

  /// The tokens issued for a user signed in to a client
  record UserTokens(IssuedToken accessToken, IdToken idToken) {}

  EventType<Request, Void> TokenRequested = BasicEventType.of(
      "Token requested",
      UUID.fromString("b6e1d4a9-2c75-4f08-9a3e-7d5b1c8f0e62"),
      DataType.json(Request.class),
      DataType.none()
  );
  EventType<IssuedToken, IssuedToken> TokenIssued = BasicEventType.of(
      "Token issued",
      UUID.fromString("2d9a7e3c-5f18-4b64-a0d2-8e6c4b1f9a75"),
      DataType.json(IssuedToken.class)
  );
  EventType<Refusal, Refusal> TokenRefused = BasicEventType.of(
      "Token refused",
      UUID.fromString("6a3f0c8e-1b47-4d92-b5e6-4c7d2a9e8f13"),
      DataType.json(Refusal.class)
  );

  EventType<CodeRequest, Void> CodeRedemptionRequested = BasicEventType.of(
      "Code redemption requested",
      UUID.fromString("d93d1c03-54d1-419a-8165-c84f1c0197b9"),
      DataType.json(CodeRequest.class),
      DataType.none()
  );
  /// The client redeeming a code is authenticated. Never stored, as it chooses the transition of the outcome.
  EventType<Tuple3<CodeRequest, UUID, ZonedDateTime>, Void> CodeRedemptionAuthenticated = BasicEventType.of(
      "Code redemption authenticated",
      UUID.fromString("3cfde0fc-c751-4b60-9c75-8025e29175a1"),
      DataType.unknown(),
      DataType.none()
  );
  EventType<UserTokens, UserTokens> UserTokensIssued = BasicEventType.of(
      "User tokens issued",
      UUID.fromString("8702fa9a-a8d9-4d71-8c1d-d8cc0c6b86ae"),
      DataType.json(UserTokens.class)
  );
  /// The UserInfo endpoint is called with the access token of the token request
  EventType<Void, Void> UserInfoRequested = BasicEventType.of(
      "User info requested",
      UUID.fromString("bf5cc0e6-d1cc-4bef-9fe9-ca3d07da929c")
  );

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(Duration lifetime) {
    return Map.of(
        TokenRequest, Map.of(
            Begin, List.of(
                onEvent(TokenRequested).to(States.Issued)
                    .assemble(c -> tuple(c.input(), c.log().entityId(), c.timestamp()))
                    .trigger(Clients.ClientLookedUp).on(Client).identifiedBy(d -> idEvent(ClientIdClaimed, d.t1().clientId()))
                    // An unknown client and a wrong secret are refused alike, so the response does not tell which
                    // client ids exist. isRejected() marks a rejected read as handled.
                    .when(d -> d.t2().isRejected() || !d.t2().isAccepted() || !Secrets.matches(d.t1().t1().secretHash(), registration(d.t2()).secretHash())).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "invalid_client", "Client authentication failed")
                    )
                    .when(d -> !registration(d.t2()).grantTypes().contains(Clients.ClientCredentials)).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "unauthorized_client", "The client may not use the client_credentials grant")
                    )
                    .when(d -> d.t1().t1().scope() != null && !registration(d.t2()).scopes().containsAll(Scopes.parse(d.t1().t1().scope()))).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "invalid_scope", "The requested scope exceeds the scope of the client")
                    )
                    .otherwise(
                        onEvent(TokenIssued).to(States.Issued)
                            .assembleInput()
                            .trigger(Inbox.ReplyCreated).with(d -> Reply.json(200, d)).on(Request).identifiedBy(entityIdFromSession())
                            .output(Tuple2::t1),
                        d -> issue(d.t1(), registration(d.t2()), lifetime)
                    ),
                onEvent(CodeRedemptionRequested).to(States.UserTokensIssued)
                    .assemble(c -> tuple(c.input(), c.log().entityId(), c.timestamp()))
                    .trigger(Clients.ClientLookedUp).on(Client).identifiedBy(d -> idEvent(ClientIdClaimed, d.t1().clientId()))
                    .when(d -> d.t2().isRejected() || !d.t2().isAccepted() || !Secrets.matches(d.t1().t1().secretHash(), registration(d.t2()).secretHash())).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "invalid_client", "Client authentication failed")
                    )
                    .when(d -> !registration(d.t2()).grantTypes().contains(Clients.AuthorizationCode)).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "unauthorized_client", "The client may not use the authorization_code grant")
                    )
                    // The client is authenticated before the code is redeemed, so a client can't use up the codes
                    // of others without its secret
                    .otherwise(redeem(lifetime), Tuple2::t1)
            ),
            States.Issued, List.of(),
            States.UserTokensIssued, List.of(
                onEvent(UserInfoRequested).toSelf()
                    .assemble((_, log) -> log.one(UserTokensIssued))
                    .trigger(Inbox.ReplyCreated).with(d -> Reply.json(200, userInfo(d.idToken()))).on(Request).identifiedBy(entityIdFromSession())
                    .output(_ -> null)
            ),
            States.Refused, List.of()
        )
    );
  }

  /// Redeems the code of an authenticated client. The code is used up by the attempt, so a code which is refused, e.g.
  /// for a wrong code verifier, can't be tried again. Every refusal is the `invalid_grant` of RFC 6749 section 5.2.
  private static TransitionModel<Tuple3<CodeRequest, UUID, ZonedDateTime>, Void> redeem(Duration lifetime) {
    return onEvent(CodeRedemptionAuthenticated).to(States.UserTokensIssued)
        .assembleInput()
        .trigger(Authorizations.CodeRedeemed).with(d -> new Redemption(d.t2().toString(), d.t1().clientId())).on(Authorizations.Authorization).identifiedBy(d -> idEvent(Authorizations.CodeClaimed, d.t1().codeHash()))
        .when(d -> d.t2().isRejected() || !d.t2().isAccepted()).then(
            refuse(),
            d -> invalidGrant(d.t1().t1(), "The authorization code is invalid or used")
        )
        .when(d -> !granted(d.t2()).request().clientId().equals(d.t1().t1().clientId())).then(
            refuse(),
            d -> invalidGrant(d.t1().t1(), "The authorization code was issued to another client")
        )
        .when(d -> !granted(d.t2()).request().redirectUri().equals(d.t1().t1().redirectUri())).then(
            refuse(),
            d -> invalidGrant(d.t1().t1(), "The redirect URI differs from that of the authorization request")
        )
        .when(d -> !Objects.equals(granted(d.t2()).request().codeChallenge(), d.t1().t1().codeChallenge())).then(
            refuse(),
            d -> invalidGrant(d.t1().t1(), d.t1().t1().codeChallenge() == null ? "The code verifier is missing" : "The code verifier does not match the code challenge")
        )
        .when(d -> d.t1().t3().toEpochSecond() > granted(d.t2()).authentication().issuedAt() + Authorizations.CodeLifetime.toSeconds()).then(
            refuse(),
            d -> invalidGrant(d.t1().t1(), "The authorization code has expired")
        )
        .otherwise(
            onEvent(UserTokensIssued).to(States.UserTokensIssued)
                .assembleInput()
                .trigger(Inbox.ReplyCreated).with(d -> Reply.json(200, d)).on(Request).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1),
            d -> userTokens(d.t1(), granted(d.t2()), lifetime)
        );
  }

  private static Refusal invalidGrant(CodeRequest request, String description) {
    return new Refusal(request.clientId(), "invalid_grant", description);
  }

  private static Granted granted(ProcessResult<Granted> result) {
    return result.accepted().event().getUnmarshalledData();
  }

  /// The access token, for the UserInfo endpoint, and the ID token of the user signed in to the client
  private static UserTokens userTokens(Tuple3<CodeRequest, UUID, ZonedDateTime> request, Granted granted, Duration lifetime) {
    long issuedAt = request.t3().toEpochSecond();
    long expiresAt = issuedAt + lifetime.toSeconds();
    var user = granted.authentication().user();
    var scopes = Scopes.parse(granted.request().scope());
    boolean email = scopes.contains("email");
    boolean profile = scopes.contains("profile");
    return new UserTokens(
        new IssuedToken(request.t2().toString(), request.t1().clientId(), user.subject(), granted.request().scope(), issuedAt, expiresAt),
        new IdToken(
            user.subject(),
            request.t1().clientId(),
            granted.request().nonce(),
            granted.authentication().authTime(),
            issuedAt,
            expiresAt,
            email ? user.email() : null,
            email && user.email() != null ? Boolean.TRUE.equals(user.emailVerified()) : null,
            profile ? user.name() : null
        )
    );
  }

  /// The UserInfo response of OpenID Connect Core section 5.3.2: the claims of the user in the ID token
  private static Map<String, Object> userInfo(IdToken idToken) {
    var claims = new LinkedHashMap<String, Object>();
    claims.put("sub", idToken.subject());
    if (idToken.email() != null) {
      claims.put("email", idToken.email());
      claims.put("email_verified", idToken.emailVerified());
    }
    if (idToken.name() != null)
      claims.put("name", idToken.name());
    return claims;
  }

  /// A refused token request is a warning for monitoring, as many of them may be an attempt to guess a secret
  private static TransitionModel<Refusal, Refusal> refuse() {
    return onEvent(TokenRefused).to(States.Refused).severity(Severity.Warning)
        .assembleInput()
        .trigger(Inbox.ReplyCreated).with(Tokens::reply).on(Request).identifiedBy(entityIdFromSession())
        .output(Tuple2::t1);
  }

  /// The error response of RFC 6749 section 5.2. A failed client authentication with the Authorization header is
  /// answered with 401, and the edge adds the WWW-Authenticate header with its realm.
  private static Reply reply(Refusal refusal) {
    return Reply.error(refusal.error().equals("invalid_client") ? 401 : 400, refusal.error(), refusal.description());
  }

  private static Registration registration(ProcessResult<Registration> result) {
    return result.accepted().event().getUnmarshalledData();
  }

  /// The token is issued with the requested scope, or with all the scopes of the client if none were requested
  private static IssuedToken issue(Tuple3<Request, UUID, ZonedDateTime> request, Registration client, Duration lifetime) {
    String scope = request.t1().scope() != null ?
        Scopes.format(Scopes.parse(request.t1().scope())) :
        Scopes.format(client.scopes());
    String subject = client.subject() != null ? client.subject() : client.clientId();
    long issuedAt = request.t3().toEpochSecond();
    return new IssuedToken(request.t2().toString(), client.clientId(), subject, scope, issuedAt, issuedAt + lifetime.toSeconds());
  }

}
