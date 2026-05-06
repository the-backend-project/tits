package com.github.thxmasj.statemachine.http;

import com.github.thxmasj.statemachine.BuiltinEntities.Rule;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.InputEvent;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.Validated;
import com.github.thxmasj.statemachine.http.HttpRequestRouter.HttpRequestRoute.ContentRoute;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestRouting;
import static com.github.thxmasj.statemachine.BuiltinEntities.RejectAsUnroutable;
import static com.github.thxmasj.statemachine.BuiltinEntities.RespondBadRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.RouteRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Rejected;
import static com.github.thxmasj.statemachine.BuiltinEntities.States.Routed;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static java.util.stream.Collectors.toList;

public class HttpRequestRouter {

  /// The definition of a route for an HTTP request
  ///
  /// @param <T> the type of the content in the HTTP request's body
  public record HttpRequestRoute<T, U>(
      Predicate<HttpRequestMessage> metadataPredicate,
      Function<HttpRequestMessage, Validated<T>> contentParser,
      List<ContentRoute<T, U>> contentRoutes
  ) {
    record ContentRoute<T, U>(Predicate<T> predicate, InputEvent<U> processEvent, Function<Event<?>, Validated<T>> handler) {}
  }

  public static TransitionModelBuilder.TransitionModel<?, ?> transitions(List<HttpRequestRoute<?>> routes) {
    return onEvent(RouteRequest).to(Routed)
        .assembleInput()
        .when(routes.stream().map(route -> rule(route)).collect(toList()))
        .otherwise(
            onEvent(RejectAsUnroutable).to(Rejected)
                .assembleInput()
                .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                .output(),
            _ -> "No route matching request metadata"
        );
  }

  private static <T> Rule<HttpRequestMessage, ?, ?> rule(HttpRequestRoute<T> metadataRoute) {
    return new Rule<>(
        metadataRoute.metadataPredicate(),
        onEvent(RouteRequest).to(Routed)
            .assemble(c -> metadataRoute.contentParser().apply(c.input()))
            .when(Validated::isInvalid).then(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                d -> "Invalid request body: " + d.invalid()
            )
            .when(metadataRoute.contentRoutes().stream().map(contentRoute -> contentRule(contentRoute)).collect(toList()))
            .otherwise(
                onEvent(RejectAsUnroutable).to(Rejected)
                    .assembleInput()
                    .trigger(RespondBadRequest).with(d -> d).on(RequestRouting).identifiedBy(entityIdFromSession())
                    .output(),
                _ -> "No route matching content"
            )
    );
  }

  private static <T> Rule<Validated<T>, ?, ?> contentRule(ContentRoute<T> contentRoute) {
    return new Rule<>(
        d -> d.isValid() && contentRoute.predicate().test(d.valid()),
        onEvent(RouteRequest).to(Routed)
            .assembleInput()
            //.trigger() // TODO
            .output()
    );
  }

}
