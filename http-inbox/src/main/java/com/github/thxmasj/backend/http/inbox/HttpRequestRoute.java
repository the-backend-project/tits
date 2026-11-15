package com.github.thxmasj.backend.http.inbox;

import com.github.thxmasj.backend.core.*;
import com.github.thxmasj.backend.http.inbox.HttpInbox.RoutedRequest;
import com.github.thxmasj.backend.core.message.http.*;

import java.util.*;
import java.util.function.*;
import java.util.regex.*;

import static com.github.thxmasj.backend.core.Validated.*;

/// Defines a route for an HTTP request
///
/// @param <T> the type of the data in the HTTP request's body
public record HttpRequestRoute<T>(
    Predicate<HttpRequestMessage> metadataPredicate,
    Function<HttpRequestMessage, Validated<T>> contentParser,
    List<ContentRoute<T, ?>> contentRoutes
) {

  /// @param <T> the type of the data in the HTTP request's body
  /// @param <U> the type of the input data for the route's process event
  public record ContentRoute<T, U>(
      Predicate<T> predicate,
      String predicateName,
      BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser,
      BiFunction<HttpRequestMessage, T, Authorization> authorizer,
      UUID dispatchingEventTypeId,
      EventType<U, ?> processEventType,
      EntityModel processType,
      Function<RoutedRequest<T>, U> processInput,
      BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> processSelector,
      Function<HttpRequestMessage, HttpRequestMessage> normalizer
  ) {

    public EventType<RoutedRequest<T>, Void> dispatchingEventType() {
      return BasicEventType.of(
          "Request dispatched: " + processEventType.name(),
          dispatchingEventTypeId,
          DataType.unknown(),
          DataType.none()
      );
    }

    public boolean isRollback() {
      // TODO
      return processEventType instanceof BasicEventType.Rollback && processSelector == null;
    }

    /// The existing entity with the id. An entity can't be created with an id from a request, as it would not be
    /// time-ordered.
    public static Validated<EntitySelector> entityId(String s) {
      if (s == null)
        return invalid("Entity id is missing");
      UUID uuid;
      try {
        uuid = UUID.fromString(s);
      } catch (IllegalArgumentException e) {
        return invalid("Entity id is invalid: " + e.getMessage());
      }
      return valid(EntitySelector.entityId(uuid));
    }

    /// The existing entity with the id
    public static Validated<EntitySelector> entityId(UUID uuid) {
      if (uuid == null)
        return invalid("Entity id is missing");
      return valid(EntitySelector.entityId(uuid));
    }

    public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> newEntity() {
      return (_, _) -> new Valid<>(EntitySelector.newEntity());
    }

    /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
    /// it would not be time-ordered.
    public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> parseEntityId(
        String requestLinePattern,
        int captureGroup
    ) {
      return parseEntityId(Pattern.compile(requestLinePattern), captureGroup);
    }

    /// The existing entity with the id in the request line. An entity can't be created with an id from a request, as
    /// it would not be time-ordered.
    public static <T> BiFunction<HttpRequestMessage, T, Validated<EntitySelector>> parseEntityId(
        Pattern requestLinePattern,
        int captureGroup
    ) {
      return (request, _) -> {
        Matcher matcher = requestLinePattern.matcher(request.requestLine());
        String s = matcher.find() ? matcher.group(captureGroup) : null;
        if (s == null)
          return new Invalid<>("Unable to extract entity id from request line");
        try {
          return new Valid<>(EntitySelector.entityId(UUID.fromString(s)));
        } catch (IllegalArgumentException e) {
          return new Invalid<>("Entity id is not a valid UUID");
        }
      };
    }

    public static <T> BiFunction<HttpRequestMessage, T, Validated<String>> parseMessageId(
        String requestLinePattern,
        int captureGroup
    ) {
      return parseMessageId(Pattern.compile(requestLinePattern), captureGroup);
    }

    public static <T> BiFunction<HttpRequestMessage, T, Validated<String>> parseMessageId(
        Pattern requestLinePattern,
        int captureGroup
    ) {
      return (HttpRequestMessage request, T _) -> {
        Matcher matcher = requestLinePattern.matcher(request.requestLine());
        String s = matcher.find() ? matcher.group(captureGroup) : null;
        if (s == null)
          return new Invalid<>("Unable to extract message id from request line");
        return new Valid<>(s);
      };
    }

    public static <T, U> ContentRoute<T, U> anyContent(
        BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser,
        BiFunction<HttpRequestMessage, T, Authorization> authorizer,
        UUID dispatchingEventTypeId,
        EventType<U, ?> processEventType,
        EntityModel processType,
        Function<RoutedRequest<T>, U> processInput,
        BiFunction<HttpRequestMessage, T, ? extends Validated<? extends EntitySelector>> processSelector
    ) {
      return new ContentRoute<>(
          _ -> true,
          "[*]",
          messageIdParser,
          authorizer,
          dispatchingEventTypeId,
          processEventType,
          processType,
          processInput,
          processSelector,
          Function.identity()
      );
    }


  }
}
