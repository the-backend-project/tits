package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.AlwaysCreate;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.BasicEventType.ReadOnly;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.identityprovider.Inbox.Reply;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/// The clients of the identity provider. A client is registered by a client with the registration scope, and is
/// identified by its unique client id. A client may have a subject, which is the `sub` of its tokens instead of its
/// client id, for services which identify their callers by values a client id can't hold, e.g. URLs.
///
/// Every registration request is an entity of its own, which records who requested what, and whether the client id
/// was taken. Only accepted requests create a client.
public interface Clients {

  /// The scope allowing a client to register clients
  String RegistrationScope = "client-registration";

  EntityModel Client = EntityModel.of("Client", UUID.fromString("0f6d1c52-2b8e-4f3a-9d71-5a4c8e2b1f06"));
  EntityModel ClientRegistration = EntityModel.of("Client registration", UUID.fromString("7b3e9a14-6c0d-4e85-a2f9-1d8c5b7e3a40"));

  enum States implements State {
    Registered,
    Accepted,
    Rejected
  }

  /// @param subject      the `sub` of the client's tokens, or null for its client id
  /// @param secretHash   the hash of the client's secret. The secret itself is never seen by the state machine.
  /// @param registeredBy the client id of the client which registered the client
  record Registration(String clientId, String subject, String secretHash, List<String> scopes, String registeredBy) {}

  EventType<String, String> ClientIdClaimed = BasicEventType.of(
      "Client id claimed",
      UUID.fromString("c4a1e8f2-93b7-4d06-8e5a-2f7b1c9d4e63"),
      DataType.string()
  );
  IdEntityModel ClientIds = IdEntityModel.of("ClientIds", UUID.fromString("5e8b2d7a-1f4c-4a93-b6e0-8c3d9f2a7b15"), ClientIdClaimed);

  EventType<Registration, Registration> ClientRegistered = BasicEventType.of(
      "Client registered",
      UUID.fromString("a9d4f1c7-3e62-4b8a-9f05-6c2e8d1b4a37"),
      DataType.json(Registration.class)
  );
  EventType<Void, Registration> ClientLookedUp = new ReadOnly<>(
      "Client looked up",
      UUID.fromString("e2b7c9a4-58d1-4f6e-a3b0-9d4c1e7f2a86"),
      DataType.none(),
      DataType.json(Registration.class)
  );

  EventType<Registration, Void> RegistrationRequested = BasicEventType.of(
      "Registration requested",
      UUID.fromString("3c9f6e1b-7a24-4d58-b1e3-0f5a8c2d6e94"),
      DataType.json(Registration.class),
      DataType.none()
  );
  EventType<Tuple2<Registration, UUID>, Registration> RegistrationAccepted = BasicEventType.of(
      "Registration accepted",
      UUID.fromString("8d2a5f7c-4b91-4e36-a0c8-1e7f3b9d5a24"),
      DataType.unknown(),
      DataType.json(Registration.class)
  );
  EventType<Registration, Registration> RegistrationRejected = BasicEventType.of(
      "Registration rejected",
      UUID.fromString("1a7e4c9d-2f63-4b05-8e9a-6d3c0b8f1e72"),
      DataType.json(Registration.class)
  );

  Pattern clientIdPattern = Pattern.compile("[A-Za-z0-9._~-]{1,100}");

  /// Client ids are restricted to characters which need no encoding anywhere, e.g. in a URL or a Basic credential
  static boolean isValidClientId(String clientId) {
    return clientId != null && clientIdPattern.matcher(clientId).matches();
  }

  int maxSubjectLength = 255;

  /// Subjects can be any text, e.g. a URL or a URN, but not blank, too long or with control characters
  static boolean isValidSubject(String subject) {
    return subject != null
        && !subject.isBlank()
        && subject.length() <= maxSubjectLength
        && subject.codePoints().noneMatch(Character::isISOControl);
  }

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Client, Map.of(
            Begin, List.of(
                onEvent(ClientRegistered).to(States.Registered).assembleInput().output(d -> d)
            ),
            States.Registered, List.of(
                onEvent(ClientLookedUp).toSelf().assemble((_, log) -> log.one(ClientRegistered)).output(d -> d)
            )
        ),
        ClientIds, ClientIds.transitions(),
        ClientRegistration, Map.of(
            Begin, List.of(
                // The client gets a new entity id, which its id entity is created with
                onEvent(RegistrationRequested).to(States.Accepted)
                    .assemble(c -> tuple(c.input(), UUID.randomUUID()))
                    .trigger(ClientIdClaimed).with(d -> d.t1().clientId()).on(ClientIds).identifiedBy(d -> entityId(d.t2(), AlwaysCreate))
                    .when(d -> d.t2().isIdExists()).then(
                        onEvent(RegistrationRejected).to(States.Rejected)
                            .assembleInput()
                            .trigger(Inbox.ReplyCreated).with(d -> Reply.error(409, "invalid_client_metadata", "Client id " + d.clientId() + " is taken")).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(Tuple2::t1),
                        d -> d.t1().t1()
                    )
                    .otherwise(
                        onEvent(RegistrationAccepted).to(States.Accepted)
                            .assemble(c -> tuple(c.input(), c.timestamp().toEpochSecond()))
                            .trigger(ClientRegistered).with(d -> d.t1().t1()).on(Client).identifiedBy(d -> newEntityId(d.t1().t2()))
                            .trigger(Inbox.ReplyCreated).with(d -> Reply.json(201, registered(d.t1().t1().t1(), d.t1().t2()))).on(RequestDispatching).identifiedBy(entityIdFromSession())
                            .output(d -> d.t1().t1().t1().t1()),
                        Tuple2::t1
                    )
            ),
            States.Accepted, List.of(),
            States.Rejected, List.of()
        )
    );
  }

  /// The client information response of RFC 7591 section 3.2.1, without the secret, which is added outside the state
  /// machine
  private static Map<String, Object> registered(Registration registration, long issuedAt) {
    var response = new LinkedHashMap<String, Object>();
    response.put("client_id", registration.clientId());
    if (registration.subject() != null)
      response.put("subject", registration.subject());
    response.put("client_id_issued_at", issuedAt);
    response.put("scope", Scopes.format(registration.scopes()));
    response.put("grant_types", List.of("client_credentials"));
    response.put("token_endpoint_auth_method", "client_secret_basic");
    return response;
  }

}
