package com.github.thxmasj.backend.http.outbox;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/// The state common to the [Dispatcher] implementations.
abstract class BaseDispatcher<X, I> {

  private final HttpOutboxRequest<?> outbox;
  private final EventType<I, ?> requestDispatched;
  private final BiFunction<DispatchContext, X, Mono<I>> inputCreator;
  private final List<EntityModel> entityModels;

  BaseDispatcher(
      HttpOutboxRequest<?> outbox,
      EventType<I, ?> requestDispatched,
      BiFunction<DispatchContext, X, Mono<I>> inputCreator,
      List<EntityModel> entityModels
  ) {
    this.outbox = outbox;
    this.requestDispatched = requestDispatched;
    this.inputCreator = inputCreator;
    this.entityModels = entityModels;
  }

  public HttpOutboxRequest<?> outbox() {
    return outbox;
  }

  public EventType<I, ?> requestDispatched() {
    return requestDispatched;
  }

  public Mono<I> input(DispatchContext context, X data) {
    return inputCreator.apply(context, data);
  }

  public List<EntityModel> entityModels() {
    return entityModels;
  }

  static <X, I> BiFunction<DispatchContext, X, Mono<I>> reactive(Function<X, I> inputCreator) {
    return (_, x) -> Mono.just(inputCreator.apply(x));
  }

  static void requireNoRollbackModels(AtMostOnce<?, ?> outbox) {
    if (outbox.rollbackOnAcceptedModel() != null || outbox.rollbackOnUnknownModel() != null)
      throw new IllegalArgumentException(outbox.name() + " has rollback models, use a dispatcher with rollback");
  }

  static void requireRollbackModels(AtMostOnce<?, ?> outbox) {
    if (outbox.rollbackOnAcceptedModel() == null && outbox.rollbackOnUnknownModel() == null)
      throw new IllegalArgumentException(outbox.name() + " has no rollback models, use a dispatcher without rollback");
  }

  /// The outbox request and its rollback models
  static List<EntityModel> withRollbackModels(AtMostOnce<?, ?> outbox) {
    var entityModels = new ArrayList<EntityModel>();
    entityModels.add(outbox);
    if (outbox.rollbackOnAcceptedModel() != null)
      entityModels.add(outbox.rollbackOnAcceptedModel());
    if (outbox.rollbackOnUnknownModel() != null)
      entityModels.add(outbox.rollbackOnUnknownModel());
    return List.copyOf(entityModels);
  }

}
