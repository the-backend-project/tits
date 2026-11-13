# HTTP inboxes

An inbox is a set of entity models whose entities are the requests the application receives, drawn in the same kind
of state diagram as those of the application (see the [state diagrams](#state-diagrams)).

The `HttpInboxListener` receives HTTP requests with reactor-netty and passes them to the entity models of the inbox.
A `RequestRouting` entity is created for every request, and matches it against the routes of the application. A route
validates the request, and gives the event type and entity it triggers. A `RequestDispatching` entity is created for
every routed request, which triggers the event on the application's entity and waits for it to respond. The response
is an event on the `RequestDispatching` entity, of a `ResponseEventType`, which the listener sends to the client.

Requests carrying a message id are deduplicated through the `MessageIds` id entity model: a retried request gets the
response of the first one, without triggering anything again. Filters on the listener can authenticate, log or rewrite
requests before they reach the inbox.

## Access tokens

Routes requiring an access token, e.g. one issued by the [identity provider](identity-provider.md), take
`HttpInbox.bearerToken(verifier, scopes...)` as their authorizer, which authorizes the `sub` of the bearer token if the
verifier finds it valid and it has the scopes. The `AccessTokenVerifier` is given the keys of the issuer, its issuer
URI and optionally the audience the tokens must have:

```java
var verifier = AccessTokenVerifier.fetchingKeys(
    URI.create("https://idp.example.com/.well-known/jwks.json"),
    URI.create("https://idp.example.com"),
    "payments-api",
    Clock.systemUTC()
);
var authorizer = HttpInbox.bearerToken(verifier, "payments");
```

The verifier accepts JWT access tokens as profiled by [RFC 9068](https://www.rfc-editor.org/rfc/rfc9068): of type
`at+jwt`, signed with RS256 by a key of the issuer, of the issuer, not expired, for the audience if one is given, and
with the claims `sub` and `client_id`. A refused request is answered as
[RFC 6750](https://www.rfc-editor.org/rfc/rfc6750#section-3) says, with a `WWW-Authenticate` header: 401 and the
challenge `Bearer` without a bearer token, 401 and the error `invalid_token` with a token that is invalid or expired,
and 403 and the error `insufficient_scope` with a token lacking a scope. An authorizer returns an `Authorization`,
either `Authorized` with the subject or `Refused` with the status code, challenge and reason of the response;
`Authorization.refused(reason)` refuses with 400. The keys are fetched once, so the verifier must be created again to see keys the
issuer has added since.

## State diagrams

<object data="../images/com.github.thxmasj.backend.http.inbox.RequestRouting.svg" type="image/svg+xml" width="100%"></object>

[Open the RequestRouting diagram in full size](images/com.github.thxmasj.backend.http.inbox.RequestRouting.svg)

<object data="../images/com.github.thxmasj.backend.http.inbox.RequestDispatching.svg" type="image/svg+xml" width="100%"></object>

[Open the RequestDispatching diagram in full size](images/com.github.thxmasj.backend.http.inbox.RequestDispatching.svg)
