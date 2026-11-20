package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.message.http.HttpRequest;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtMostOnce] outbox request which can't be rolled back. See [AtMostOnceWithRollbackDispatcher] for one which can.
public final class AtMostOnceDispatcher<X, RQ> extends BaseDispatcher<X, HttpRequest<RQ>, AtMostOnce<RQ, Void>> implements Dispatcher<X, HttpRequest<RQ>> {

  private AtMostOnceDispatcher(AtMostOnce<RQ, Void> outbox, BiFunction<DispatchContext, X, Mono<HttpRequest<RQ>>> inputCreator) {
    super(outbox, outbox.requestDispatched(), inputCreator);
  }

  public static <X, RQ> AtMostOnceDispatcher<X, RQ> of(AtMostOnce<RQ, Void> outbox, Function<X, HttpRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, from the data and the context of the transition
  /// dispatching the request.
  public static <X, RQ> AtMostOnceDispatcher<X, RQ> reactive(
      AtMostOnce<RQ, Void> outbox,
      BiFunction<DispatchContext, X, Mono<HttpRequest<RQ>>> inputCreator
  ) {
    requireNoRollbackModels(outbox);
    return new AtMostOnceDispatcher<>(outbox, inputCreator);
  }

}
