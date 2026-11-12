package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.idEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.identityprovider.Clients.Client;
import static com.github.thxmasj.backend.identityprovider.Clients.ClientId;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Access tokens for the client credentials grant of RFC 6749 section 4.4. Every token request is an entity of its
/// own, which records the token issued or why it was refused. Its entity id is the `jti` of the token.
public interface Tokens {

  EntityModel TokenRequest = EntityModel.of("Token request", UUID.fromString("4f8c2a6e-9d13-4b70-8e5f-3a1d7c9b2e48"));

  enum States implements State {
    Issued,
    Refused
  }

  /// A token request of a client which is yet to be authenticated
  ///
  /// @param scope the requested scope, or null for all the scopes of the client
  record Request(String clientId, String secretHash, String scope) {}

  /// @param issuedAt  in seconds since the epoch
  /// @param expiresAt in seconds since the epoch
  record IssuedToken(String id, String clientId, String scope, long issuedAt, long expiresAt) {}

  /// @param error an error code of RFC 6749 section 5.2
  record Refusal(String clientId, String error, String description) {}

  EventType<Request, Void> RequestToken = BasicEventType.of(
      "Request token",
      UUID.fromString("b6e1d4a9-2c75-4f08-9a3e-7d5b1c8f0e62"),
      DataType.json(Request.class),
      DataType.none()
  );
  EventType<IssuedToken, IssuedToken> IssueToken = BasicEventType.of(
      "Issue token",
      UUID.fromString("2d9a7e3c-5f18-4b64-a0d2-8e6c4b1f9a75"),
      DataType.json(IssuedToken.class)
  );
  EventType<Refusal, Refusal> RefuseToken = BasicEventType.of(
      "Refuse token",
      UUID.fromString("6a3f0c8e-1b47-4d92-b5e6-4c7d2a9e8f13"),
      DataType.json(Refusal.class)
  );

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(Duration lifetime) {
    return Map.of(
        TokenRequest, Map.of(
            Begin, List.of(
                onEvent(RequestToken).to(States.Issued)
                    .assemble(c -> tuple(c.input(), c.log().entityId().value(), c.timestamp()))
                    .trigger(Clients.Get).on(Client).identifiedBy(d -> idEvent(ClientId, d.t1().clientId()))
                    // An unknown client and a wrong secret are refused alike, so the response does not tell which
                    // client ids exist. isRejected() marks a rejected read as handled.
                    .when(d -> d.t2().isRejected() || !d.t2().isAccepted() || !Secrets.matches(d.t1().t1().secretHash(), registration(d.t2()).secretHash())).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "invalid_client", "Client authentication failed")
                    )
                    .when(d -> d.t1().t1().scope() != null && !registration(d.t2()).scopes().containsAll(Scopes.parse(d.t1().t1().scope()))).then(
                        refuse(),
                        d -> new Refusal(d.t1().t1().clientId(), "invalid_scope", "The requested scope exceeds the scope of the client")
                    )
                    .otherwise(
                        onEvent(IssueToken).to(States.Issued)
                            .assembleInput()
                            .trigger(Inbox.SendReply).with(d -> Reply.json(200, d)).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(Tuple2::t1),
                        d -> issue(d.t1(), registration(d.t2()), lifetime)
                    )
            ),
            States.Issued, List.of(),
            States.Refused, List.of()
        )
    );
  }

  private static TransitionModel<Refusal, Refusal> refuse() {
    return onEvent(RefuseToken).to(States.Refused)
        .assembleInput()
        .trigger(Inbox.SendReply).with(Tokens::reply).on(RequestDispatching).identifiedBy(entityIdFromSession())
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
    long issuedAt = request.t3().toEpochSecond();
    return new IssuedToken(request.t2().toString(), client.clientId(), scope, issuedAt, issuedAt + lifetime.toSeconds());
  }

}
