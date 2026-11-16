package com.github.thxmasj.backend.identityprovider;

import static com.github.thxmasj.backend.core.Validated.invalid;
import static com.github.thxmasj.backend.core.Validated.valid;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute.anyContent;
import static com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute.newEntity;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestRoutingTransitions;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Validated;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage;
import com.github.thxmasj.backend.core.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.backend.core.message.http.HttpResponseMessage;
import com.github.thxmasj.backend.http.inbox.Authorization;
import com.github.thxmasj.backend.http.inbox.HttpInbox.CustomResponse;
import com.github.thxmasj.backend.http.inbox.HttpRequestRoute;
import com.github.thxmasj.backend.http.inbox.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.backend.http.inbox.ResponseEventType;
import com.github.thxmasj.backend.identityprovider.Clients.Registration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/// The HTTP requests handled by the state machine. They arrive through the [Edge], which authenticates them and puts
/// what it found in trusted headers, so no credential ever enters the event log.
public interface Inbox {

  /// The client id of the client requesting a token, which is yet to be authenticated
  String ClientIdHeader = "X-Idp-Client-Id";
  /// The hash of the secret of the client requesting a token, or of the secret generated for a client being registered
  String SecretHashHeader = "X-Idp-Client-Secret-Hash";
  /// The client id of the authenticated client registering a client
  String RegisteredByHeader = "X-Idp-Registered-By";
  /// The hash of an authorization code: of the code redeemed at the token endpoint, or of the code generated for a
  /// user who signed in
  String CodeHashHeader = "X-Idp-Code-Hash";
  /// The S256 PKCE challenge of the code verifier of a token request
  String CodeChallengeHeader = "X-Idp-Code-Challenge";
  /// The entity id of the authorization a user returns to from the upstream provider
  String AuthorizationIdHeader = "X-Idp-Authorization";
  /// The `jti` of the verified access token the UserInfo endpoint is called with
  String TokenIdHeader = "X-Idp-Token-Id";
  List<String> trustedHeaders = List.of(
      ClientIdHeader,
      SecretHashHeader,
      RegisteredByHeader,
      CodeHashHeader,
      CodeChallengeHeader,
      AuthorizationIdHeader,
      TokenIdHeader
  );

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

  /// A redirect of the user to a client, to which the edge adds the code if the user signed in
  record Redirect(String redirectUri, Map<String, String> parameters) {}

  ResponseEventType<Reply> ReplyCreated = new ResponseEventType<>(
      "Reply created",
      UUID.fromString("9e5b3d1f-7a28-4c64-8f0b-2d6e9a4c1b57"),
      DataType.json(Reply.class)
  );

  List<HttpRequestRoute<?>> routes = List.of(
      // The edge tells the grants apart by the code, which it replaces by its hash
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/token") && m.headerValue(CodeHashHeader) != null,
          Inbox::codeRequest,
          List.of(anyContent(
              null,
              null,
              UUID.fromString("53b447da-38bc-43d6-a9f2-ea3ed7e1b3fa"),
              Tokens.CodeRedemptionRequested,
              Tokens.TokenRequest,
              r -> r.body(),
              newEntity()
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/token"),
          Inbox::tokenRequest,
          List.of(anyContent(
              null,
              null,
              UUID.fromString("d1c8f5a2-3e97-4b06-a4d1-5f2b8e7c9a63"),
              Tokens.TokenRequested,
              Tokens.TokenRequest,
              r -> r.body(),
              newEntity()
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/clients"),
          Inbox::registration,
          List.of(anyContent(
              null,
              (m, _) -> Authorization.authorizedElse(m.headerValue(RegisteredByHeader), "Unauthenticated"),
              UUID.fromString("f7a2c6e9-4d15-4b83-9e0a-1c5d8b3f6e24"),
              Clients.RegistrationRequested,
              Clients.ClientRegistration,
              r -> r.body(),
              newEntity()
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.method() == Method.GET && m.uri().getPath().equals("/authorize"),
          Inbox::authorizationRequest,
          List.of(anyContent(
              null,
              null,
              UUID.fromString("709a9c55-28ab-4aaf-83b6-b5755fc4c50b"),
              Authorizations.AuthorizationRequested,
              Authorizations.Authorization,
              r -> r.body(),
              newEntity()
          ))
      ),
      // The edge has signed the user in with the upstream provider, and generated the code if the user did
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/callback") && m.headerValue(CodeHashHeader) != null,
          Inbox::signIn,
          List.of(anyContent(
              null,
              null,
              UUID.fromString("0c0f5f0e-4a5d-4b8e-9d6b-2a7c3e1f8d42"),
              Authorizations.UserAuthenticated,
              Authorizations.Authorization,
              r -> r.body(),
              (m, _) -> ContentRoute.entityId(m.headerValue(AuthorizationIdHeader))
          ))
      ),
      new HttpRequestRoute<>(
          m -> m.method() == Method.POST && m.uri().getPath().equals("/callback"),
          r -> json(r, Authorizations.Failure.class),
          List.of(anyContent(
              null,
              null,
              UUID.fromString("e4a7b2c9-6d1f-4e3a-8b5c-9f0d2e6a1b73"),
              Authorizations.UserAuthenticationFailed,
              Authorizations.Authorization,
              r -> r.body(),
              (m, _) -> ContentRoute.entityId(m.headerValue(AuthorizationIdHeader))
          ))
      ),
      // The edge has verified the access token
      new HttpRequestRoute<>(
          m -> m.method() == Method.GET && m.uri().getPath().equals("/userinfo"),
          r -> r.headerValue(TokenIdHeader) != null ? valid(r.headerValue(TokenIdHeader)) : invalid("Unauthenticated"),
          List.of(anyContent(
              null,
              null,
              UUID.fromString("1d6e8f3a-9b2c-4c7d-a5e0-3f8b6d1c9e24"),
              Tokens.UserInfoRequested,
              Tokens.TokenRequest,
              _ -> null,
              (m, _) -> ContentRoute.entityId(m.headerValue(TokenIdHeader))
          ))
      )
  );

  List<CustomResponse<?>> customResponses = List.of(
      new CustomResponse<>(
          ReplyCreated,
          c -> new HttpResponseMessage(
              c.input().status(),
              reasonPhrase(c.input().status()),
              Map.of(
                  "Content-Type", "application/json;charset=UTF-8",
                  // Responses with credentials must not be cached, RFC 6749 section 5.1
                  "Cache-Control", "no-store",
                  "X-Correlation-Id", c.correlationId()
              ),
              c.input().body().getBytes(UTF_8)
          )
      )
  );

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions(Duration tokenLifetime) {
    var transitions = new HashMap<EntityModel, Map<State, List<TransitionModel<?, ?>>>>();
    transitions.putAll(Clients.transitions());
    transitions.putAll(Tokens.transitions(tokenLifetime));
    transitions.putAll(Authorizations.transitions());
    transitions.put(RequestRouting, requestRoutingTransitions(routes));
    transitions.put(RequestDispatching, requestDispatchingTransitions(routes, customResponses));
    transitions.put(MessageIds, MessageIds.transitions());
    return transitions;
  }

  /// The form is validated by the edge, which also authenticates the client
  private static Validated<Tokens.Request> tokenRequest(HttpRequestMessage request) {
    String clientId = request.headerValue(ClientIdHeader);
    String secretHash = request.headerValue(SecretHashHeader);
    if (clientId == null || secretHash == null)
      return invalid("Client credentials missing");
    try {
      return valid(new Tokens.Request(clientId, secretHash, Form.parse(request.body()).get("scope")));
    } catch (IllegalArgumentException e) {
      return invalid(e.getMessage());
    }
  }

  /// The form is validated by the edge, which also authenticates the client and replaces the code and the code
  /// verifier by their hashes
  private static Validated<Tokens.CodeRequest> codeRequest(HttpRequestMessage request) {
    String clientId = request.headerValue(ClientIdHeader);
    String secretHash = request.headerValue(SecretHashHeader);
    String codeHash = request.headerValue(CodeHashHeader);
    if (clientId == null || secretHash == null || codeHash == null)
      return invalid("Client credentials or code missing");
    try {
      String redirectUri = Form.parse(request.body()).get("redirect_uri");
      if (redirectUri == null)
        return invalid("redirect_uri is missing");
      return valid(new Tokens.CodeRequest(clientId, secretHash, codeHash, redirectUri, request.headerValue(CodeChallengeHeader)));
    } catch (IllegalArgumentException e) {
      return invalid(e.getMessage());
    }
  }

  /// An authorization request of RFC 6749 section 4.1.1 and OpenID Connect Core section 3.1.2.1. A request without a
  /// valid client id and redirect URI is invalid, as the client can't be told by a redirect. Other problems are
  /// told the client by a redirect with the error, once the client and the redirect URI are found valid.
  private static Validated<Authorizations.Request> authorizationRequest(HttpRequestMessage request) {
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

  /// The user signed in, as told by the edge
  private static Validated<Authorizations.SignIn> signIn(HttpRequestMessage request) {
    Validated<Authorizations.User> user = json(request, Authorizations.User.class);
    if (user.isInvalid())
      return invalid(user.invalidReason());
    if (user.validValue().subject() == null)
      return invalid("The user has no subject");
    return valid(new Authorizations.SignIn(user.validValue(), request.headerValue(CodeHashHeader)));
  }

  private static <T> Validated<T> json(HttpRequestMessage request, Class<T> type) {
    try {
      return valid(DataType.objectMapper.readValue(request.body(), type));
    } catch (JacksonException | IllegalArgumentException e) {
      return invalid("Body is not " + type.getSimpleName());
    }
  }

  /// A registration request, e.g. `{"client_id": "orders", "scope": "orders:read orders:write"}`, with an optional
  /// `subject` for the `sub` of the client's tokens. A client without `scope` has no scopes.
  private static Validated<Registration> registration(HttpRequestMessage request) {
    String registeredBy = request.headerValue(RegisteredByHeader);
    String secretHash = request.headerValue(SecretHashHeader);
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
