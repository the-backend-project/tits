package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.message.http.TypedHttpRequest;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtLeastOnce] outbox request.
public final class AtLeastOnceDispatcher<X, RQ> extends BaseDispatcher<X, TypedHttpRequest<RQ>> implements Dispatcher<X, TypedHttpRequest<RQ>> {

  private AtLeastOnceDispatcher(AtLeastOnce<RQ> outbox, BiFunction<DispatchContext, X, Mono<TypedHttpRequest<RQ>>> inputCreator) {
    super(outbox, outbox.requestDispatched(), inputCreator, Map.of(outbox, outbox.transitions()));
  }

  public static <X, RQ> AtLeastOnceDispatcher<X, RQ> of(AtLeastOnce<RQ> outbox, Function<X, TypedHttpRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, from the data and the context of the transition
  /// dispatching the request.
  public static <X, RQ> AtLeastOnceDispatcher<X, RQ> reactive(
      AtLeastOnce<RQ> outbox,
      BiFunction<DispatchContext, X, Mono<TypedHttpRequest<RQ>>> inputCreator
  ) {
    return new AtLeastOnceDispatcher<>(outbox, inputCreator);
  }

}
