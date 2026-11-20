package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.message.http.HttpRequest;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// A [Dispatcher] of requests to an [AtLeastOnce] outbox request.
public final class AtLeastOnceDispatcher<X, RQ> extends BaseDispatcher<X, HttpRequest<RQ>, AtLeastOnce<RQ>> implements Dispatcher<X, HttpRequest<RQ>> {

  private final AtLeastOnce<RQ> atLeastOnce;

  private AtLeastOnceDispatcher(AtLeastOnce<RQ> outbox, BiFunction<DispatchContext, X, Mono<HttpRequest<RQ>>> inputCreator) {
    super(outbox, outbox.requestDispatched(), inputCreator);
    this.atLeastOnce = outbox;
  }

  public static <X, RQ> AtLeastOnceDispatcher<X, RQ> of(AtLeastOnce<RQ> outbox, Function<X, HttpRequest<RQ>> inputCreator) {
    return reactive(outbox, BaseDispatcher.reactive(inputCreator));
  }

  /// A dispatcher with a creator which creates the input reactively, from the data and the context of the transition
  /// dispatching the request.
  public static <X, RQ> AtLeastOnceDispatcher<X, RQ> reactive(
      AtLeastOnce<RQ> outbox,
      BiFunction<DispatchContext, X, Mono<HttpRequest<RQ>>> inputCreator
  ) {
    return new AtLeastOnceDispatcher<>(outbox, inputCreator);
  }

  /// Sends an accepted or dead request again, as a retry would, on the entity selected by
  /// [HttpOutboxRequest#dispatchedBy]. See [AtLeastOnce#requestRedispatched()].
  public EventType<Void, HttpRequest<RQ>> requestRedispatched() {
    return atLeastOnce.requestRedispatched();
  }

}
