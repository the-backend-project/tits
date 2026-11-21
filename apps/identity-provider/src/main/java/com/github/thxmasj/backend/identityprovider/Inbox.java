package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInboxRequest.newEntity;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.http.HttpDataType;
import com.github.thxmasj.backend.http.HttpRequest.Method;
import com.github.thxmasj.backend.http.HttpRequest;
import com.github.thxmasj.backend.http.HttpResponse;
import com.github.thxmasj.backend.http.Masking;
import com.github.thxmasj.backend.http.inbox.Form;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.http.inbox.HttpInboxRequest;
import com.github.thxmasj.backend.http.inbox.OpenIdConnectClient;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/// The HTTP requests of the identity provider, every one of which is an inbox request, without a filter in front of
/// the inbox. What they do with credentials, see [Endpoints], keeps the credentials out of the event log: the secrets,
/// codes and tokens are only in the session of a request, and masked in the requests and responses stored. The
/// requests answered at once, e.g. the health check, the public keys and the metadata, and the requests refused before
/// they reach the entities of the identity provider, are not stored.
public interface Inbox {

  /// The header of the registrations of the identity provider itself, e.g. of the bootstrap client, with a key only
  /// the process knows, which the event log masks
  String InternalHeader = "X-Idp-Internal";
  /// The client id of the client registering a client, of a registration of the identity provider itself
  String RegisteredByHeader = "X-Idp-Registered-By";
  /// The hash of the secret of the client registered, of a registration of the identity provider itself
  String SecretHashHeader = "X-Idp-Client-Secret-Hash";

  /// The longest `state` and `nonce` accepted, which are stored in the event log
  int maxParameterLength = 1000;
  Pattern codeChallengePattern = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

  /// A JSON response to a request
  record Reply(int status, String body) {

    static Reply json(int status, Object body) {
      return new Reply(status, DataType.objectMapper.writeValueAsString(body));
    }

    static Reply error(int status, String error, String description) {
      var body = new LinkedHashMap<String, String>();
      body.put("error", error);
      body.put("error_description", description);
      return json(status, body);
    }

  }

  /// A redirect of the user to a client, with the code if the user signed in
  record Redirect(String redirectUri, Map<String, String> parameters) {}

  ResponseEventType<Reply> ReplyCreated = new ResponseEventType<>(
      "Reply created",
      UUID.fromString("9e5b3d1f-7a28-4c64-8f0b-2d6e9a4c1b57"),
      DataType.json(Reply.class)
  );

  /// A request answered at once with the response
  EventType<HttpResponse<byte[]>, Void> AnswerRequested = BasicEventType.of(
      "Answer requested",
      UUID.fromString("c7e3a9f1-4b2d-4e86-a0c5-8d1f6b3e9a27"),
      HttpDataType.forResponse(),
      DataType.none()
  );
  ResponseEventType<HttpResponse<byte[]>> Answered = new ResponseEventType<>(
      "Answered",
      UUID.fromString("1b8f5d3a-6c9e-4a27-b4d1-e2a7c0f9b356"),
      HttpDataType.forResponse()
  );

  enum AnswerStates implements State {Answered}

  /// The answers of the requests answered at once, which are entities of the session only
  EntityModel Answers = EntityModel.notStored("Answers", UUID.fromString("5d0a8e2c-7f4b-4c19-9e63-b1f8d4a2c705"), () -> Map.of(
      EntityModel.Begin, List.of(
          onEvent(AnswerRequested).to(AnswerStates.Answered)
              .assembleInput()
              .trigger(Answered).with(d -> d).on(HttpInbox.Request).identifiedBy(entityIdFromSession())
              .output()
      ),
      AnswerStates.Answered, List.of()
  ));

  /// The requests answered at once, which are not stored: the health check, the public keys and the metadata, the
  /// requests refused before they reach the entities of the identity provider, e.g. without client authentication,
  /// and those of unknown paths or methods
  static HttpInboxRequest<HttpResponse<byte[]>> immediateHttpRequest(Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> endpoints.immediate(m) != null)
        .contentParser(m -> valid(endpoints.immediate(m)))
        .notStored()
        .process(AnswerRequested, Answers, RoutedRequest::body, newEntity())
        .response(Answered, TransitionContext::input)
        .build("ImmediateHttpRequest", UUID.fromString("8a4c2e6f-1d9b-4f73-a5e0-3c7b9d1f2e84"));
  }

  /// A request redeeming an authorization code, `POST /token` with the grant type `authorization_code`, processed on
  /// the token requests. The code and the code verifier are masked in the event log, and the response, with the
  /// tokens signed.
  static HttpInboxRequest<Tokens.CodeRequest> codeHttpRequest(EntityModel tokenRequest, Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.POST && m.uri().getPath().equals("/token") && Endpoints.isCodeRequest(m))
        .contentParser(endpoints::codeRequest)
        .masking(Masking.formFields("code", "code_verifier").andResponseBody())
        .process(Tokens.CodeRedemptionRequested, tokenRequest, r -> r.body(), newEntity())
        .response(ReplyCreated, endpoints::userTokensResponse)
        .build("CodeHttpRequest", UUID.fromString("53b447da-38bc-43d6-a9f2-ea3ed7e1b3fa"));
  }

  /// A token request, `POST /token`, processed on the token requests. The response, with the token signed, is masked
  /// in the event log.
  static HttpInboxRequest<Tokens.Request> tokenHttpRequest(EntityModel tokenRequest, Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.POST && m.uri().getPath().equals("/token"))
        .contentParser(endpoints::tokenRequest)
        .masking(Masking.responseBody())
        .process(Tokens.TokenRequested, tokenRequest, r -> r.body(), newEntity())
        .response(ReplyCreated, endpoints::tokenResponse)
        .build("TokenHttpRequest", UUID.fromString("d1c8f5a2-3e97-4b06-a4d1-5f2b8e7c9a63"));
  }

  /// A registration of the identity provider itself, e.g. of the bootstrap client, with the hash of a secret of its
  /// own, see [#internalRegistration(String, String, byte\[\])]
  HttpInboxRequest<Clients.RegistrationRequest> InternalRegistrationHttpRequest = HttpInboxRequest
      .matching(m -> m.method() == Method.POST && m.uri().getPath().equals("/clients") && Endpoints.isInternal(m))
      .contentParser(m -> {
        Validated<Registration> registration = registration(m, m.headerValue(RegisteredByHeader), m.headerValue(SecretHashHeader));
        return registration.isInvalid() ? invalid(registration.invalidReason()) : valid(new Clients.RegistrationRequest(registration.validValue(), null));
      })
      .masking(Masking.headers(InternalHeader))
      .process(Clients.RegistrationRequested, Clients.ClientRegistration, r -> r.body(), newEntity())
      .response(ReplyCreated, Inbox::reply)
      .build("InternalRegistrationHttpRequest", UUID.fromString("2e6c0a4f-8b3d-4f91-a7e5-d9c1b5f3a068"));

  /// A client registration request, `POST /clients`, of a client with the registration scope. The response, with the
  /// secret generated for the client, is masked in the event log.
  static HttpInboxRequest<Clients.RegistrationRequest> registrationHttpRequest(Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.POST && m.uri().getPath().equals("/clients"))
        .contentParser(endpoints::registration)
        .masking(Masking.responseBody())
        .process(Clients.RegistrationRequested, Clients.ClientRegistration, r -> r.body(), newEntity())
        .response(ReplyCreated, Endpoints::registered)
        .build("RegistrationHttpRequest", UUID.fromString("f7a2c6e9-4d15-4b83-9e0a-1c5d8b3f6e24"));
  }

  /// An authorization request of a user, `GET /authorize`, which redirects the user to the upstream provider with the
  /// secret of the sign-in in a cookie, which the event log masks
  static HttpInboxRequest<Authorizations.Request> authorizationHttpRequest(Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.GET && m.uri().getPath().equals("/authorize"))
        .contentParser(Inbox::authorizationRequest)
        .process(Authorizations.AuthorizationRequested, Authorizations.Authorization, r -> r.body(), newEntity())
        .response(ReplyCreated, endpoints::authorizationResponse)
        .build("AuthorizationHttpRequest", UUID.fromString("709a9c55-28ab-4aaf-83b6-b5755fc4c50b"));
  }

  /// The user returning from the upstream provider, `GET /callback`, whose code is redeemed by an upstream sign-in of
  /// the session only. The code of the upstream provider, the cookie and the redirect to the client with the code
  /// generated for it are masked in the event log.
  static HttpInboxRequest<Authorizations.Callback> callbackHttpRequest(Endpoints endpoints, EntityModel upstreamSignIn) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.GET && m.uri().getPath().equals("/callback"))
        .contentParser(Endpoints::callback)
        .masking(Masking.queryParameters("code").andHeaders("Location"))
        .process(Authorizations.CallbackReceived, upstreamSignIn, r -> r.body(), newEntity())
        .response(ReplyCreated, endpoints::callbackResponse)
        .build("CallbackHttpRequest", UUID.fromString("0c0f5f0e-4a5d-4b8e-9d6b-2a7c3e1f8d42"));
  }

  /// A UserInfo request, `GET /userinfo`, with a valid access token, processed on the token request which issued the
  /// token
  static HttpInboxRequest<String> userInfoHttpRequest(EntityModel tokenRequest, Endpoints endpoints) {
    return HttpInboxRequest
        .matching(m -> m.method() == Method.GET && m.uri().getPath().equals("/userinfo"))
        .contentParser(endpoints::tokenId)
        .process(Tokens.UserInfoRequested, tokenRequest, (_, tokenId) -> HttpInboxRequest.entityId(tokenId))
        .response(ReplyCreated, endpoints::userInfoResponse)
        .build("UserInfoHttpRequest", UUID.fromString("1d6e8f3a-9b2c-4c7d-a5e0-3f8b6d1c9e24"));
  }

  static HttpResponse<byte[]> reply(TransitionContext<Reply> c) {
    return new HttpResponse<>(
        c.input().status(),
        reasonPhrase(c.input().status()),
        Map.of(
            "Content-Type", "application/json;charset=UTF-8",
            // Responses with credentials must not be cached, RFC 6749 section 5.1
            "Cache-Control", "no-store",
            "X-Correlation-Id", c.correlationId()
        ),
        c.input().body().getBytes(UTF_8)
    );
  }

  /// The inbox of the identity provider for its entity models only, e.g. for a backoffice or a diagram. Its requests
  /// can't be served, as it has no keys.
  static HttpInbox inbox(Duration tokenLifetime) {
    return inbox(tokenLifetime, new Endpoints(null, null, Map.of()), true);
  }

  /// The inbox of the identity provider, whose requests trigger events on the clients, the token requests, whose tokens
  /// live as long as the token lifetime, and the authorizations
  ///
  /// @param keys                 the keys tokens are signed and verified with
  /// @param upstream             the client of the provider users sign in with, or null if users can't sign in
  /// @param importedSecretHashes the BCrypt hashes of the secrets of the imported clients, by client id
  static HttpInbox inbox(Duration tokenLifetime, Keys keys, OpenIdConnectClient upstream, Map<String, String> importedSecretHashes) {
    return inbox(tokenLifetime, new Endpoints(keys, upstream, importedSecretHashes), upstream != null);
  }

  private static HttpInbox inbox(Duration tokenLifetime, Endpoints endpoints, boolean signIn) {
    EntityModel tokenRequest = Tokens.tokenRequest(tokenLifetime);
    // In the order they are matched in, as the first matching one takes a request
    var requests = new ArrayList<HttpInboxRequest<?>>(List.of(
        immediateHttpRequest(endpoints),
        codeHttpRequest(tokenRequest, endpoints),
        tokenHttpRequest(tokenRequest, endpoints),
        InternalRegistrationHttpRequest,
        registrationHttpRequest(endpoints)
    ));
    if (signIn)
      requests.addAll(List.of(
          authorizationHttpRequest(endpoints),
          callbackHttpRequest(endpoints, Authorizations.upstreamSignIn(endpoints.upstream())),
          userInfoHttpRequest(tokenRequest, endpoints)
      ));
    return HttpInbox.of(requests);
  }

  /// A registration of the identity provider itself, as the registrar, with the hash of the secret of the client,
  /// e.g. of the bootstrap client
  static HttpRequest<byte[]> internalRegistration(String registeredBy, String secretHash, byte[] body) {
    return Endpoints.internalRegistration(registeredBy, secretHash, body);
  }

  /// An authorization request of RFC 6749 section 4.1.1 and OpenID Connect Core section 3.1.2.1. A request without a
  /// valid client id and redirect URI is invalid, as the client can't be told by a redirect. Other problems are
  /// told the client by a redirect with the error, once the client and the redirect URI are found valid.
  static Validated<Authorizations.Request> authorizationRequest(HttpRequest<byte[]> request) {
    Map<String, String> query;
    try {
      String raw = request.uri().getRawQuery();
      query = Form.parse(raw != null ? raw.getBytes(UTF_8) : null);
    } catch (IllegalArgumentException e) {
      return invalid(e.getMessage());
    }
    String clientId = query.get("client_id");
    if (!Clients.isValidClientId(clientId))
      return invalid("client_id is missing or invalid");
    String redirectUri = query.get("redirect_uri");
    if (redirectUri == null || redirectUri.length() > Clients.maxRedirectUriLength)
      return invalid("redirect_uri is missing or too long");
    String state = query.get("state");
    if (state != null && state.length() > maxParameterLength)
      state = null;
    String scope = query.get("scope");
    String nonce = query.get("nonce");
    String challenge = query.get("code_challenge");
    String method = query.get("code_challenge_method");
    String responseType = query.get("response_type");
    String prompt = query.get("prompt");
    String[] error = null;
    if (query.get("state") != null && state == null)
      error = new String[] {"invalid_request", "state is too long"};
    else if (responseType == null)
      error = new String[] {"invalid_request", "response_type is missing"};
    else if (!responseType.equals("code"))
      error = new String[] {"unsupported_response_type", "Only the response type code is supported"};
    else if (query.containsKey("response_mode") && !query.get("response_mode").equals("query"))
      error = new String[] {"invalid_request", "Only the response mode query is supported"};
    else if (query.containsKey("request"))
      error = new String[] {"request_not_supported", "Request objects are not supported"};
    else if (query.containsKey("request_uri"))
      error = new String[] {"request_uri_not_supported", "Request objects are not supported"};
    else if (scope == null || !Scopes.isValid(scope) || !Scopes.parse(scope).contains("openid"))
      error = new String[] {"invalid_scope", "scope must include openid"};
    else if (nonce != null && nonce.length() > maxParameterLength)
      error = new String[] {"invalid_request", "nonce is too long"};
    else if (challenge == null && method != null)
      error = new String[] {"invalid_request", "code_challenge is missing"};
    else if (challenge != null && !"S256".equals(method))
      error = new String[] {"invalid_request", "code_challenge_method must be S256"};
    else if (challenge != null && !codeChallengePattern.matcher(challenge).matches())
      error = new String[] {"invalid_request", "code_challenge is invalid"};
    // Without a session the user can't be signed in without interaction
    else if (prompt != null && List.of(prompt.split(" ")).contains("none"))
      error = new String[] {"login_required", "The user must sign in"};
    return valid(new Authorizations.Request(
        clientId,
        redirectUri,
        error == null ? scope : null,
        state,
        error == null ? nonce : null,
        error == null ? challenge : null,
        error != null ? error[0] : null,
        error != null ? error[1] : null
    ));
  }

  /// A registration request, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`, with an optional
  /// `subject` for the `sub` of the client's tokens. A client without `scope` has no scopes.
  ///
  /// @param registeredBy the client id of the client registering the client
  /// @param secretHash   the hash of the secret of the client
  static Validated<Registration> registration(HttpRequest<byte[]> request, String registeredBy, String secretHash) {
    if (registeredBy == null || secretHash == null)
      return invalid("Unauthenticated");
    JsonNode body;
    try {
      body = request.body() != null ? DataType.objectMapper.readTree(request.body()) : null;
    } catch (JacksonException e) {
      return invalid("Body is not JSON");
    }
    if (body == null || !body.isObject())
      return invalid("Body is not a JSON object");
    JsonNode clientId = body.get("client_id");
    if (clientId == null || !clientId.isString() || !Clients.isValidClientId(clientId.asString()))
      return invalid("client_id must be 1 to 100 of the characters A-Z, a-z, 0-9, '.', '_', '~' and '-'");
    // A client without scopes, which is imported, has no scope
    JsonNode scope = body.get("scope");
    if (scope != null && (!scope.isString() || !Scopes.isValid(scope.asString())))
      return invalid("scope must be a space separated list of scopes");
    JsonNode subject = body.get("subject");
    if (subject != null && (!subject.isString() || !Clients.isValidSubject(subject.asString())))
      return invalid("subject must be a string of 1 to " + Clients.maxSubjectLength + " characters, not blank and without control characters");
    List<String> grantTypes = strings(body.get("grant_types"));
    List<String> redirectUris = strings(body.get("redirect_uris"));
    if (body.has("grant_types") && grantTypes == null)
      return invalid("grant_types must be an array of strings");
    if (body.has("redirect_uris") && redirectUris == null)
      return invalid("redirect_uris must be an array of strings");
    grantTypes = grantTypes != null ? grantTypes.stream().distinct().toList() : List.of(Clients.ClientCredentials);
    redirectUris = redirectUris != null ? redirectUris.stream().distinct().toList() : List.of();
    String invalidGrant = Clients.invalidGrant(grantTypes, redirectUris);
    if (invalidGrant != null)
      return invalid(invalidGrant);
    return valid(new Registration(
        clientId.asString(),
        subject != null ? subject.asString() : null,
        secretHash,
        scope != null ? Scopes.parse(scope.asString()) : List.of(),
        registeredBy,
        grantTypes,
        redirectUris
    ));
  }

  /// The strings of a JSON array, or null if the node is absent or not an array of strings
  private static List<String> strings(JsonNode node) {
    if (node == null || !node.isArray())
      return null;
    var strings = new ArrayList<String>();
    for (JsonNode element : node) {
      if (!element.isString())
        return null;
      strings.add(element.asString());
    }
    return strings;
  }

  private static String reasonPhrase(int status) {
    return switch (status) {
      case 200 -> "OK";
      case 201 -> "Created";
      case 302 -> "Found";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 409 -> "Conflict";
      default -> "Status " + status;
    };
  }

}
