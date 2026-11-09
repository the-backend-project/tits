package com.github.thxmasj.statemachine;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.Filter;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ModelContext;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public class TransitionModel<I, O> {

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InitialChangeContext<I>>, Mono<OutputChangeContext<O>>> chain;

  @Override
  public int hashCode() {
    return Objects.hash(modelContext.eventType(), modelContext.to());
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj instanceof TransitionModel<?, ?> other) {
      return Objects.equals(this.modelContext.to(), other.modelContext.to())
          && Objects.equals(this.modelContext.eventType(), other.modelContext.eventType());
    }
    return false;
  }

  @Override
  public String toString() {
    return String.format("onEvent(%s).to(%s)", (modelContext.eventType() != null ? modelContext.eventType().name() : "N/A") , modelContext.to() != null ? modelContext.to().name() : "<self>");
  }

  TransitionModel(
      ModelContext<I, O> modelContext,
      Function<Mono<InitialChangeContext<I>>, Mono<OutputChangeContext<O>>> chain
  ) {
    this.modelContext = modelContext;
    this.chain = chain;
  }

  public TransitionModel<Data, Data> reverseModel() {
    return modelContext.reverseModel();
  }

  public TransitionModel<Tuple4<I, EntityModel, EventType<?, ?>, String>, O> rejectModel() {
    return null;
  }

  public Mono<OutputChangeContext<O>> calculate(InitialChangeContext<I> initialChangeContext) {
    TransitionModelBuilder.log(modelContext, "calculate [" + eventType().name() + "] on [" + initialChangeContext.log().entityModel().name() + ":" + initialChangeContext.log().entityId().value() + "] (input: " + initialChangeContext.input() + ")");
    return chain.apply(Mono.just(initialChangeContext));
  }

  Mono<OutputChangeContext<O>> calculateReverse(Mono<InitialChangeContext<I>> initial) {
    return chain.apply(initial);
  }

  public EventType<I, O> eventType() {
    return modelContext.eventType();
  }

  public State toState() {
    return modelContext.to();
  }

  @SafeVarargs
  public static Map<State, List<TransitionModel<?, ?>>> mergeModels(
      Map<State, List<TransitionModel<?, ?>>> ...lists
  ) {
    return Arrays.stream(lists).flatMap(l -> l.entrySet().stream())
        .collect(Collectors.toMap(
            Entry::getKey,
            Entry::getValue,
            (e1, e2) -> Stream.concat(e1.stream(), e2.stream()).toList()
        ));
  }

  public List<Filter<?,?,?>> filters() {
    return modelContext.filters();
  }

  public List<EventTrigger<?, ?, ?>> triggers() {
    return modelContext.triggers();
  }

  public List<Action<?>> actions() {
    return modelContext.actions();
  }
}
