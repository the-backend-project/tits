package com.github.thxmasj.backend.http.inbox;

import jakarta.annotation.Nullable;

/// The outcome of a route's authorizer: the subject a request is authorized for, or why it is refused
public sealed interface Authorization {

  /// @param subject the subject the request is authorized for, e.g. the client sending it
  record Authorized(String subject) implements Authorization {}

  /// A refused request, answered with the status code and, if there is a challenge, a `WWW-Authenticate` header
  /// with it
  ///
  /// @param statusCode the status code of the response
  /// @param challenge the value of the `WWW-Authenticate` header of the response, if any
  /// @param reason the detail of the response
  record Refused(int statusCode, @Nullable String challenge, String reason) implements Authorization {}

  static Authorization authorized(String subject) {
    return new Authorized(subject);
  }

  /// A refusal answered with 400 Bad Request
  static Authorization refused(String reason) {
    return new Refused(400, null, reason);
  }

  /// Authorized for the subject, or refused with 400 Bad Request if there is none
  static Authorization authorizedElse(@Nullable String subject, String reason) {
    return subject != null ? authorized(subject) : refused(reason);
  }

  /// The subject the request is authorized for
  ///
  /// @throws IllegalStateException if the request is refused
  default String subject() {
    throw new IllegalStateException("Not authorized: " + refused().reason());
  }

  default boolean isRefused() {
    return this instanceof Refused;
  }

  /// The refusal, if the request is refused
  ///
  /// @throws IllegalStateException if the request is authorized
  default Refused refused() {
    if (this instanceof Refused refused)
      return refused;
    throw new IllegalStateException("Not refused");
  }
}
