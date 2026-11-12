package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.TransitionModelBuilder.log;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.TransitionModelBuilder.Filter;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ModelContext;
import com.github.thxmasj.backend.core.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.Tuples.Tuple4;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

/// The model of a transition of an entity model on an event type, built by [TransitionModelBuilder]. It calculates the
/// steps of the transition from its input step to its output step.
public class TransitionModel<I, O> {

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain;

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
      Function<Mono<InputStep<I>>, Mono<OutputStep<O>>> chain
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

  public Mono<OutputStep<O>> calculate(InputStep<I> inputStep) {
    log(modelContext, "calculate [" + eventType().name() + "] on [" + inputStep.log().entityModel().name() + ":" + inputStep.log().entityId() + "] (input: " + inputStep.input() + ")");
    return chain.apply(Mono.just(inputStep));
  }

  Mono<OutputStep<O>> calculateReverse(Mono<InputStep<I>> initial) {
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
