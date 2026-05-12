package com.github.thxmasj.statemachine.http;

import static com.github.thxmasj.statemachine.BuiltinEntities.MessageId;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestDispatching;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestRouting;
import static com.github.thxmasj.statemachine.BuiltinEntities.RejectAsUnroutable;
import static com.github.thxmasj.statemachine.BuiltinEntities.RespondBadRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.RouteRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Rejected;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Routed;
import static com.github.thxmasj.statemachine.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EntitySelector.secondaryId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BuiltinEntities.RequestParser.ParsedRequest;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.EventType.DataType;
import com.github.thxmasj.statemachine.GuardedTransition;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.Validated.Invalid;
import com.github.thxmasj.statemachine.Validated.Valid;
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpRequestRouter {

  /// Defines a route for an HTTP request
  ///
  /// @param <T> the type of the data in the HTTP request's body
  /// @param <U> the type of the input data for the route's process event
  public record HttpRequestRoute<T, U>(
      Predicate<HttpRequestMessage> metadataPredicate,
      Function<HttpRequestMessage, Validated<T>> contentParser,
      List<ContentRoute<T, U>> contentRoutes
  ) {
    public record ContentRoute<T, U>(
        Predicate<T> predicate,
        String predicateName,
        BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser,
        boolean authorize,
        UUID dispatchingEventTypeId,
        EventType<U, ?> processEventType,
        EntityModel processType,
        Function<ParsedRequest<T>, U> processInput,
        Function<ParsedRequest<T>, Validated<EntitySelector<ParsedRequest<T>>>> processSelector
    ) {
      public EventType<ParsedRequest<T>, Void> dispatchingEventType() {
        return BasicEventType.of(
            "Dispatch: " + processEventType.name(),
            dispatchingEventTypeId,
            new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, Object.class),
            Void.class
        );
      }
      public boolean isRollback() {
        // TODO
        return processEventType instanceof BasicEventType.Rollback && processSelector == null;
      }

      public static <T> Function<ParsedRequest<T>, Validated<EntitySelector<ParsedRequest<T>>>> parseEntityId(
          String requestLinePattern,
          int captureGroup
      ) {
        return (ParsedRequest<T> d) -> {
          Matcher matcher = Pattern.compile(requestLinePattern).matcher(d.request().requestLine());
          String s = matcher.find() ? matcher.group(captureGroup) : null;
          if (s == null) return new Invalid<>("Unable to extract entity id from request line");
          try {
            return new Valid<>(entityId(UUID.fromString(s)));
          } catch (IllegalArgumentException e) {
            return new Invalid<>("Entity id is not a valid UUID");
          }
        };
      }

      public static <T> BiFunction<HttpRequestMessage, T, Validated<String>> parseMessageId(
          String requestLinePattern,
          int captureGroup
      ) {
        return (HttpRequestMessage request, T _) -> {
          Matcher matcher = Pattern.compile(requestLinePattern).matcher(request.requestLine());
          String s = matcher.find() ? matcher.group(captureGroup) : null;
          if (s == null) return new Invalid<>("Unable to extract message id from request line");
          return new Valid<>(s);
        };
      }

      public static <T, U> ContentRoute<T, U> anyContent(
          BiFunction<HttpRequestMessage, T, Validated<String>> messageIdParser,
          boolean authorize,
          UUID dispatchingEventTypeId,
          EventType<U, ?> processEventType,
          EntityModel processType,
          Function<ParsedRequest<T>, U> processInput,
          Function<ParsedRequest<T>, Validated<EntitySelector<ParsedRequest<T>>>> processSelector
      ) {
        return new ContentRoute<>(
            _ -> true,
            "[*]",
            messageIdParser,
            authorize,
            dispatchingEventTypeId,
            processEventType,
            processType,
            processInput,
            processSelector
        );
      }


    }
  }

  public static TransitionModelBuilder.TransitionModel<?, ?> initialTransitionFor(List<HttpRequestRoute<?, ?>> routes) {
    return onEvent(RouteRequest).to(Routed)
        .assembleInput()
        .choice(routes.stream().map(HttpRequestRouter::metadataRouteTransition).collect(toList()), d -> d)
        .otherwise(
            onEvent(RejectAsUnroutable).to(Rejected)
                .assembleInput()
                .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(_ -> entityIdFromSession())
                .output(),
            _ -> "No route matching request metadata"
        );
  }

  private static <T> GuardedTransition<HttpRequestMessage, HttpRequestMessage, Void> metadataRouteTransition(HttpRequestRoute<T, ?> metadataRoute) {
    return new GuardedTransition<>(
        metadataRoute.metadataPredicate(),
        onEvent(RouteRequest).to(Routed)
            .assemble(c -> tuple(
                    c.input(),
                    metadataRoute.contentParser() != null ? metadataRoute.contentParser().apply(c.input()) : null
                )
            )
            .when(d -> d.t2() != null && d.t2().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(_ -> entityIdFromSession())
                    .output(),
                d -> "Invalid request body: " + d.t2().invalid()
            )
            .choice(
                metadataRoute.contentRoutes().stream().map(contentRoute -> contentRouteTransition(contentRoute)).collect(toList()),
                d -> tuple(d.t1(), d.t2().valid())
            )
            .otherwise(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(_ -> entityIdFromSession())
                    .output(),
                _ -> "No route matching content"
            )
    );
  }

  private static <T, U> GuardedTransition<Tuple2<HttpRequestMessage, Validated<T>>, Tuple2<HttpRequestMessage, T>, Void> contentRouteTransition(ContentRoute<T, U> contentRoute) {
    EventType<Tuple2<HttpRequestMessage, T>, Void> contentRoutingEvent = BasicEventType.of(
        contentRoute.predicateName(),
        //contentRoute.processEventType().id(),
        contentRoute.dispatchingEventTypeId,
        new DataType<>(new TypeReference<>() {}, HttpRequestMessage.class, Object.class),
        Void.class
    );
    return new GuardedTransition<>(
        d -> d.t2().isValid() && contentRoute.predicate().test(d.t2().valid()),
        onEvent(contentRoutingEvent).to(Routed)
            .assemble(d -> tuple(
                d.input().t1(),
                d.input().t2(),
                contentRoute.messageIdParser() != null ? contentRoute.messageIdParser().apply(d.input().t1(), d.input().t2()) : null,
                contentRoute.authorize() ? ParsedAuthorizationClaims.claimsFromBearerToken(d.input().t1()) : null
            ))
            .when(d -> d.t3() != null && d.t3().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(_ -> entityIdFromSession())
                    .output(),
                d -> "Unable to parse message id: " + d.t3().invalid()
            )
            .when(d -> d.t4() != null && d.t4().isInvalid()).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(_ -> entityIdFromSession())
                    .output(),
                d -> "Unable to parse Authorization header: " + d.t4().invalid()
            )
            .otherwise(
                onEvent(contentRoute.dispatchingEventType()).to(Routed)
                    .assembleInput()
                    .trigger(contentRoute.dispatchingEventType()).with(d -> d).on(RequestDispatching)
                    .identifiedBy(_ ->
                        contentRoute.isRollback() ?
                            secondaryId(MessageId, ParsedRequest::messageId, CreateIfNotExists) :
                            newEntityId()
                    )
                    .output(),
               d -> {
                  String clientId = d.t4() != null ? d.t4().valid().subject() : "N/A";
                  String messageId = d.t3() != null ? d.t3().valid() : null;
                return new ParsedRequest<>(
                    d.t1(),
                    d.t2(),
                    messageId != null ? new MessageId(clientId, messageId) : null,
                    d.t4() != null ? d.t4().valid() : null
                );
              }
            )
    );
  }

}
