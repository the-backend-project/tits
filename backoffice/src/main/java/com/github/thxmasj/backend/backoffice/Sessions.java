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
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;

/// The sessions of the users of the backoffice. Every sign-in is an entity of its own, which records who signed in,
/// or was refused, and every page they viewed, so the event log tells who looked at what. A session is created only
/// when the provider has signed a user in, so a request of someone who hasn't signed in writes nothing.
///
/// The requests reach the state machine through the [Backoffice] filter only, which signs the users in with the
/// OpenID Connect provider and puts what it found in trusted headers. The token in the session cookie never enters the
/// event log, only its hash, which is an id of the session, so a session is found by the cookie of the user.
public interface Sessions {

  /// The most events a session has, after which the user must sign in again. The event numbers of an entity are
  /// `SMALLINT`, so a session can't take more page views than that.
  int maxEvents = 30_000;

  EntityModel Session = EntityModel.of("BackofficeSession", UUID.fromString("4b6f2c8e-1d3a-4f57-9e0b-7a2c5d8f3e61"));

  enum States implements State {
    SignedIn,
    Refused,
    SignedOut,
    Expired
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

  /// A user signed in, with the hash of the token of the session cookie and the lifetime of the session
  record SignIn(User user, String tokenHash, long lifetimeSeconds) {}

  /// A user signed in
  ///
  /// @param expiresAt when the session expires, in seconds since the epoch
  record SignedIn(User user, long expiresAt) {}

  /// A user signed in, but whose claims don't permit using the backoffice
  ///
  /// @param reason why the user is refused, e.g. the claim missing
  record Refusal(User user, String reason) {}

  /// A page the user requested
  ///
  /// @param path the path and query of the page
  record View(String path) {}

  /// The response of the state machine to the [Backoffice] filter
  record Reply(int status, String body) {

    static Reply json(int status, Object body) {
      return new Reply(status, DataType.objectMapper.writeValueAsString(body));
    }

  }

  EventType<SignIn, SignedIn> UserSignedIn = BasicEventType.of(
      "User signed in",
      UUID.fromString("7e2b9d4f-3c18-4a6e-b5f0-1d8c6a2e9b73"),
      DataType.json(SignIn.class),
      DataType.json(SignedIn.class)
  );
  EventType<Refusal, Refusal> SignInRefused = BasicEventType.of(
      "Sign-in refused",
      UUID.fromString("a5d0c7e3-9f21-4b84-8e6a-3c1f5b9d7e20"),
      DataType.json(Refusal.class)
  );
  /// A page requested, which is viewed if the session is valid. It is never stored, as one of its outcomes always is.
  EventType<View, Void> ViewRequested = BasicEventType.of(
      "View requested",
      UUID.fromString("1f9c6e3b-8a2d-4d70-b4e5-9c0a7f3d5b18"),
      DataType.json(View.class),
      DataType.none()
  );
  EventType<View, View> PageViewed = BasicEventType.of(
      "Page viewed",
      UUID.fromString("6d3a8f1c-2e5b-4b96-a7c0-8e4f2d9b1a35"),
      DataType.json(View.class)
  );
  /// The session expired, as told the user requesting the page
  EventType<View, View> SessionExpired = BasicEventType.of(
      "Session expired",
      UUID.fromString("b2e7c4a9-5f06-4d1b-8c93-0a6e3f8d2b57"),
      DataType.json(View.class)
  );
  /// A page requested with the cookie of a session which has ended
  EventType<View, View> ViewRefused = BasicEventType.of(
      "View refused",
      UUID.fromString("9a4d1e7b-6c3f-4a28-b0e5-5d2c8f4a1e69"),
      DataType.json(View.class)
  );
  EventType<Void, Void> UserSignedOut = BasicEventType.of(
      "User signed out",
      UUID.fromString("3c8e5b2a-7d4f-4e10-9b6c-2f1a8d5e7c03")
  );

  /// The hash of the token of the session cookie, which is the id of the session
  EventType<String, String> TokenClaimed = BasicEventType.of(
      "Token claimed",
      UUID.fromString("f0a6d3c9-4e2b-4f85-a1d7-8b5c2e9f6a14"),
      DataType.string()
  );
  IdEntityModel Tokens = IdEntityModel.of("BackofficeTokens", UUID.fromString("5e1b8d4a-9c7f-4a32-8e60-4d2f7b1c9a85"), TokenClaimed);

  ResponseEventType<Reply> ReplyCreated = new ResponseEventType<>(
      "Backoffice reply created",
      UUID.fromString("d7c2f9e5-1a6b-4c83-9d40-7e3b5a8c2f16"),
      DataType.json(Reply.class)
  );

  /// The path of the backoffice, under which its pages are served
  String Path = "/backoffice";
  /// The hash of the token of the session cookie
  String TokenHashHeader = "X-Backoffice-Token-Hash";
  /// Tells that the user signed in, but is refused
  String RefusedHeader = "X-Backoffice-Refused";
  List<String> trustedHeaders = List.of(TokenHashHeader, RefusedHeader);

  /// The user returning from the provider, signed in and permitted to use the backoffice
  HttpInboxRequest<SignIn> SignedInRequest = HttpInboxRequest
      .matching(m -> isCallback(m) && m.headerValue(TokenHashHeader) != null)
      .contentParser(Sessions::signIn)
      .process(UserSignedIn, Session, r -> r.body(), newEntity())
      .response(ReplyCreated, c -> reply(c.input(), c.correlationId()))
      .build("BackofficeSignedInRequest", UUID.fromString("2d7a4f9c-6e3b-4b15-8c02-9a5e1d7f3b48"));

  /// The user returning from the provider, signed in but refused
  HttpInboxRequest<Refusal> RefusedRequest = HttpInboxRequest
      .matching(m -> isCallback(m) && m.headerValue(RefusedHeader) != null)
      .contentParser(r -> json(r, Refusal.class))
      .process(SignInRefused, Session, r -> r.body(), newEntity())
      .response(ReplyCreated, c -> reply(c.input(), c.correlationId()))
      .build("BackofficeRefusedRequest", UUID.fromString("c9f3b6e1-2a8d-4f47-9e05-6b1d4c8a2f93"));

  /// A user signing out
  HttpInboxRequest<String> SignOutRequest = HttpInboxRequest
      .matching(m -> m.method() == Method.POST && m.uri().getPath().equals(Path + "/signout") && m.headerValue(TokenHashHeader) != null)
      .contentParser(r -> valid(r.headerValue(TokenHashHeader)))
      .process(UserSignedOut, Session, (m, _) -> valid(idEvent(TokenClaimed, m.headerValue(TokenHashHeader))))
      .response(ReplyCreated, c -> reply(c.input(), c.correlationId()))
      .build("BackofficeSignOutRequest", UUID.fromString("e6b9d2f4-8c1a-4a53-9f70-1d4e8b3c5a27"));

  /// A page requested by a user with a session cookie
  HttpInboxRequest<View> ViewRequest = HttpInboxRequest
      .matching(m -> m.method() == Method.GET && m.uri().getPath().startsWith(Path + "/") && m.headerValue(TokenHashHeader) != null)
      .contentParser(Sessions::view)
      .process(ViewRequested, Session, r -> r.body(), (m, _) -> valid(idEvent(TokenClaimed, m.headerValue(TokenHashHeader))))
      .response(ReplyCreated, c -> reply(c.input(), c.correlationId()))
      .build("BackofficeViewRequest", UUID.fromString("7f2d5a8e-3b6c-4e19-b4a7-0c8e1f5d9b62"));

  /// The inbox requests of the backoffice
  List<HttpInboxRequest<?>> requests = List.of(
      SignedInRequest,
      RefusedRequest,
      SignOutRequest,
      ViewRequest
  );

  /// The transitions of the sessions and their tokens
  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Session, Map.of(
            Begin, List.of(
                onEvent(UserSignedIn).to(States.SignedIn)
                    .assemble(c -> tuple(
                        new SignedIn(c.input().user(), c.timestamp().toEpochSecond() + c.input().lifetimeSeconds()),
                        c.input().tokenHash(),
                        c.entityId()
                    ))
                    .trigger(TokenClaimed).with(d -> d.t2()).on(Tokens).identifiedBy(d -> newEntityId(d.t3()))
                    .trigger(ReplyCreated).with(_ -> Reply.json(200, Map.of())).on(Request).identifiedBy(entityIdFromSession())
                    .output(d -> d.t1().t1().t1()),
                // Many of them may be an attempt to get in, so each is a warning
                onEvent(SignInRefused).to(States.Refused).severity(Severity.Warning)
                    .assembleInput()
                    .trigger(ReplyCreated).with(d -> Reply.json(403, Map.of("error", d.reason()))).on(Request).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1)
            ),
            States.SignedIn, List.of(
                onEvent(ViewRequested).to(States.SignedIn)
                    .assemble(c -> tuple(c.input(), c.log().one(UserSignedIn), c.timestamp().toEpochSecond(), c.log().lastEventNumber()))
                    .when(d -> d.t3() >= d.t2().expiresAt() || d.t4() >= maxEvents).then(
                        onEvent(SessionExpired).to(States.Expired)
                            .assembleInput()
                            .trigger(ReplyCreated).with(_ -> Reply.json(401, Map.of("error", "The session has expired"))).on(Request).identifiedBy(entityIdFromSession())
                            .output(Tuple2::t1),
                        d -> d.t1()
                    )
                    .otherwise(
                        onEvent(PageViewed).to(States.SignedIn)
                            .assemble(c -> tuple(c.input(), c.log().one(UserSignedIn).user()))
                            .trigger(ReplyCreated).with(d -> Reply.json(200, d.t2())).on(Request).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1()),
                        d -> d.t1()
                    ),
                onEvent(UserSignedOut).to(States.SignedOut)
                    .assembleInput()
                    .trigger(ReplyCreated).with(_ -> Reply.json(200, Map.of())).on(Request).identifiedBy(entityIdFromSession())
                    .output()
            ),
            States.SignedOut, ended(),
            States.Expired, ended(),
            States.Refused, List.of()
        ),
        Tokens, Tokens.transitions()
    );
  }

  /// A session which has ended refuses the pages requested with its cookie, and is signed out of again
  private static List<TransitionModel<?, ?>> ended() {
    return List.of(
        onEvent(ViewRequested).toSelf()
            .assembleInput()
            .otherwise(
                onEvent(ViewRefused).toSelf()
                    .assembleInput()
                    .trigger(ReplyCreated).with(_ -> Reply.json(401, Map.of("error", "The session has ended"))).on(Request).identifiedBy(entityIdFromSession())
                    .output(Tuple2::t1),
                d -> d
            ),
        onEvent(UserSignedOut).toSelf()
            .assembleInput()
            .trigger(ReplyCreated).with(_ -> Reply.json(200, Map.of())).on(Request).identifiedBy(entityIdFromSession())
            .output()
    );
  }

  private static boolean isCallback(HttpRequestMessage m) {
    return m.method() == Method.POST && m.uri().getPath().equals(Path + "/callback");
  }

  private static Validated<SignIn> signIn(HttpRequestMessage request) {
    Validated<SignIn> signIn = json(request, SignIn.class);
    if (signIn.isInvalid())
      return signIn;
    if (signIn.validValue().user() == null || signIn.validValue().user().subject() == null)
      return invalid("The user has no subject");
    // The hash is told by the trusted header, not the body
    return valid(new SignIn(signIn.validValue().user(), request.headerValue(TokenHashHeader), signIn.validValue().lifetimeSeconds()));
  }

  private static Validated<View> view(HttpRequestMessage request) {
    String query = request.uri().getRawQuery();
    return valid(new View(request.uri().getRawPath() + (query != null ? "?" + query : "")));
  }

  private static <T> Validated<T> json(HttpRequestMessage request, Class<T> type) {
    try {
      return request.body() != null ? valid(DataType.objectMapper.readValue(request.body(), type)) : invalid("Body is missing");
    } catch (JacksonException | IllegalArgumentException e) {
      return invalid("Body is not " + type.getSimpleName());
    }
  }

  private static HttpResponseMessage reply(Reply reply, String correlationId) {
    return new HttpResponseMessage(
        reply.status(),
        "Status " + reply.status(),
        Map.of("Content-Type", "application/json;charset=UTF-8", "X-Correlation-Id", correlationId),
        reply.body().getBytes(UTF_8)
    );
  }

}
