package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.StateMachine.CircularChange;
import com.github.thxmasj.statemachine.StateMachine.IdentityResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.statemachine.StateMachine.RejectedEvent;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ActionChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.AssembledChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.CombinedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.IdentityChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.PendingChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.TriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.UnknownIdChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.http.inbox.HttpInbox.EventReference;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public class TransitionModelBuilder<I, T, O> {

  public record ModelContext<I, O>(
      boolean reversal,
      State to,
      EventType<I, O> eventType,
      List<EventTrigger<?, ?, ?>> triggers,
      List<Action<?>> actions,
      List<Filter<?, ?, ?>> filters,
      TransitionModel<Data, Data> reverseModel
  ) {

    public String description() {
      if (reversal)
        return "<reversal>";
      return String.format("%s -> %s", eventType.name(), to != null ? to : "<self>");
    }

  }

  public interface TransitionContext<I> {
    State from();
    ZonedDateTime timestamp();
    String correlationId();
    EventLog log();
    I input();
    default EventReference eventReference() {
      return new EventReference(log().entityId().value(), log().lastEventNumber() + 1);
    }

  }

  public sealed interface ChangeContext<T> {

    ChangeContext<?> previous();

    T stepOutput();

    default InitialChangeContext<?> firstInitialChangeContext() {
      ChangeContext<?> c = this;
      while (c.previous() != null) c = c.previous();
      if (c instanceof ChangeContext.InitialChangeContext<?> i) return i;
      throw new IllegalStateException("No first initial context");
    }

    default InitialChangeContext<?> initialChangeContext() {
      try {
        int depth = this instanceof ChangeContext.OutputChangeContext<?> ? 0 : 1;
        for (ChangeContext<?> c = this; c != null; c = c.previous()) {
          if (c instanceof ChangeContext.OutputChangeContext<?> _)
            depth++;
          if (c instanceof ChangeContext.InitialChangeContext<?> i && --depth == 0)
            return i;
        }
      } catch (Exception e) {
        throw new IllegalStateException("Failed to find initial change context: " + e);
      }
      throw new IllegalStateException("No initial context");
    }

    default InitialChangeContext<?> initialChangeContext(EntityModel entityModel) {
      InitialChangeContext<?> candidate = null;
      for (ChangeContext<?> c = this; c != null; c = c.previous()) {
        if (c instanceof ChangeContext.InitialChangeContext<?> i && i.log().entityModel().equals(entityModel)) {
          if (i.stage1() != null) return i.stage1().initialChangeContext(entityModel);
          candidate = i;
        }
      }
      if (candidate != null) return candidate;
      throw new IllegalStateException("No initial context for entity model " + entityModel.name());
    }

    default List<SecondaryId<?>> secondaryIds() {
      ArrayList<SecondaryId<?>> secondaryIds = new ArrayList<>();
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        if (changeContext instanceof ChangeContext.IdentityChangeContext<?> c)
          secondaryIds.add(c.stepOutput.accepted().id());
        changeContext = changeContext.previous();
      }
      return secondaryIds;
    }

    record InitialChangeContext<I>(
        ChangeContext<?> stage1,
        ChangeContext<?> previous,
        TransitionModel<?, ?> transitionModel,
        State from,
        int eventNumber,
        EventLog log,
        ZonedDateTime timestamp,
        String correlationId,
        I input,
        StateMachine stateMachine,
        List<IdentityResult<?>> identityResults
    ) implements ChangeContext<I>, TransitionContext<I> {
      @Override public I stepOutput() {return input;}
      @Override public String toString() {
        return "Initial " + log.entityModel().name() + "/" + log.entityId().value() + "/" + eventNumber + "/" + from.name() + "(stage1=" + (stage1 != null) + "): " + transitionModel + " with " + ofNullable(input).map(i -> i.getClass().getSimpleName()).orElse("-");
      }
      public <T> IdentityResult<T> identityResult(SecondaryId<T> id) {
        return (IdentityResult<T>)identityResults.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
      }
    }

    record AssembledChangeContext<T>(
        ChangeContext<?> previous,
        T stepOutput
    ) implements ChangeContext<T> {
      @Override public String toString() {
        return "Assembled";
      }
    }

    record TriggerChangeContext<T, O1>(
        ChangeContext<ProcessResult<O1>> previous,
        ChangeContext<T> outer
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        return tuple(outer.stepOutput(), previous.stepOutput());
      }

      @Override public String toString() {
        return "Trigger (outer: " + outer + ")";
      }

    }

    record CombinedChangeContext<T, U>(
        ChangeContext<U> previous,
        ChangeContext<T> outer
    ) implements ChangeContext<Tuple2<T, U>> {

      @Override
      public Tuple2<T, U> stepOutput() {
        return tuple(outer.stepOutput(), previous.stepOutput());
      }

      @Override public String toString() {
        return "Combined (outer: " + outer + ")";
      }

    }

    record ChoiceChangeContext<T>(
        ChangeContext<?> previous,
        ChangeContext<T> outer
    ) implements ChangeContext<T> {
      @Override public String toString() {
        return "Choice (outer: " + outer + ")";
      }

      @Override
      public T stepOutput() {
        return outer.stepOutput();
      }
    }

    record ScheduledChangeContext<T>(
        ChangeContext<?> previous,
        ChangeContext<T> outer
    ) implements ChangeContext<T> {

      @Override
      public T stepOutput() {
        return outer.stepOutput();
      }

      @Override public String toString() {
        return "Scheduled (outer: " + outer + ")";
      }
    }

    record PendingTriggerChangeContext<T, O1>(
        ChangeContext<T> previous,
        EventTrigger<T, ?, O1> eventTrigger,
        CircularChange e
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        throw new IllegalStateException("Event trigger is pending");
      }

      @Override public String toString() {
        return "Pending (" + e.toString() + ")";
      }

    }

    record IdentityChangeContext<T>(
        EntityModel entityModel,
        EntityId entityId,
        ChangeContext<?> previous,
        IdentityResult<T> stepOutput
    ) implements ChangeContext<IdentityResult<T>> {
      @Override public String toString() {
        return "Identifier: " + (stepOutput.isAccepted() ? (stepOutput.accepted().id().model().name() + ":" + stepOutput.accepted().id().data()) : stepOutput.getClass().getSimpleName());
      }
    }

    record ActionChangeContext<T, U>(
        ChangeContext<T> previous,
        ActionTrigger<U> actionTrigger
    ) implements ChangeContext<T> {
      @Override public String toString() {
        return "Action: " + actionTrigger.action().name();
      }

      @Override
      public T stepOutput() {
        return previous.stepOutput();
      }
    }

    record OutputChangeContext<T>(
        ChangeContext<?> previous,
        ProcessResult<T> stepOutput
    ) implements ChangeContext<ProcessResult<T>> {
      @Override public String toString() {
        return "Output:" + switch (stepOutput) {
          case Accepted<?> a -> "Accepted:" + a.entityModel().name() + "/" + a.event().entityId() + "/" + a.event().typeName() + "/" + a.event().eventNumber();
          case Rejected<?> r -> "Rejected:" + r.exception().entityModel().name() + "/" + r.exception().entityId().value() + r.eventType().name() + ":" + r.exception().getMessage();
          case Completed<?> c -> "Completed:" + c.choiceResult();
          case UnknownId<?> u -> "UnknownId:type=" + u.exception().secondaryId().model() + "/ev=" + u.eventType().name();
          case Pending<?, ?, ?> p -> "Pending:" + p.exception().entityModel() + "/" + p.exception().entityId().value() + ":" + p.exception().getMessage();
        };
      }

    }

    record PendingChangeContext<T, I, O>(
        ChangeContext<?> previous,
        ProcessResult.Pending<T, I, O> stepOutput
    ) implements ChangeContext<ProcessResult<O>> {
      @Override public String toString() {
        return "Pending:" + stepOutput.exception().getMessage();
      }

    }

    record RejectedChangeContext<O>(
        ChangeContext<?> previous,
        ProcessResult.Rejected<O> stepOutput
    ) implements ChangeContext<ProcessResult<O>> {
      @Override public String toString() {
        return "Rejected:" + stepOutput.exception().getMessage();
      }
    }

    record UnknownIdChangeContext<O>(
        ChangeContext<?> previous,
        ProcessResult.UnknownId<O> stepOutput
    ) implements ChangeContext<ProcessResult<O>> {
      @Override public String toString() {
        return "UnknownId:" + stepOutput.exception().getMessage();
      }
    }

  }

  public static TransitionModel<Void, State> statusOn() {
    return new TransitionModel<>(
        new ModelContext<>(false, null, BuiltinEventTypes.Status, List.of(), List.of(), List.of(), null),
        initialChangeContext -> initialChangeContext.flatMap(i -> Mono.just(new OutputChangeContext<>(
            i,
            ProcessResult.accepted(
                new Event<>(
                    i.log().entityId().value(),
                    i.log().lastEventNumber() + 1,
                    BuiltinEventTypes.Status,
                    i.timestamp(),
                    i.from
                ),
                i.log().entityModel()
            )
        )))
    );
  }

  public static TransitionModel<Data, Data> rollbackOn(EventType<Data, Data> eventType) {
    return new TransitionModel<>(
        new ModelContext<>(false, null, eventType, List.of(), List.of(), List.of(), null),
        initialChangeContext -> initialChangeContext.flatMap(i -> {
          int rollbackTo = i.stepOutput().toNumber();
          int actualRollbackTo = rollbackTo;
          if (rollbackTo < 0) {
            actualRollbackTo = i.log().lastEventNumber() + rollbackTo; // add negative
          }
          if (
            // Rollback to future event
              actualRollbackTo >= i.log().lastEventNumber()
                  // Rollback to before start
                  || actualRollbackTo < 0
                  // Rollback more than one request
                  || i.log().events().subList(actualRollbackTo, i.log().events().size()).stream().filter(e -> e.type() instanceof RequestEventType<?,?>).count() > 1
                  // Rollback from an event which is not the last
                  //|| rollbackFrom != i.log().lastEventNumber()
          ) {
            return Mono.just(new OutputChangeContext<>(
                i,
                ProcessResult.rejected(
                    eventType,
                    new RejectedEvent(
                        eventType,
                        i.log().entityModel(),
                        i.log().entityId(),
                        i.from(),
                        actualRollbackTo
                    )
                )
            ));
          }
          EventLog log = i.log();
          var effectiveEvents = log.effectiveEvents();
          List<Event<?>> eventsToRollback = effectiveEvents.subList(actualRollbackTo, effectiveEvents.size());
          Traverser traverser = i.stateMachine.traverser(log.entityModel());
          Mono<ChangeContext<?>> currentContext = Mono.just(i);
          for (var event : eventsToRollback.reversed()) {
            var tm = traverser.transitionForEventNumber(log, event.eventNumber());
            if (tm == null) {
              throw new IllegalStateException(String.format(
                  "No transition found for event number %d",
                  event.eventNumber()
              ));
            }
            if (tm.reverseModel() != null) {
              currentContext = currentContext.flatMap(c -> tm.reverseModel().calculateReverse(
                  Mono.just(new InitialChangeContext<>(
                      null,
                      c,
                      tm.reverseModel(),
                      i.from(),
                      event.eventNumber(),
                      i.log(),
                      i.timestamp(),
                      i.correlationId(),
                      i.stepOutput(),
                      i.stateMachine(),
                      List.of()
                  ))
              ));
            }
          }
          return currentContext.map(c -> new OutputChangeContext<>(
              c,
              ProcessResult.accepted(
                  new Event<>(
                      i.log().entityId().value(),
                      i.log().lastEventNumber() + 1,
                      eventType,
                      i.timestamp(),
                      i.stepOutput()
                  ),
                  i.log().entityModel()
              )
          ));
        })
    );
  }

  public static final class WithEvent<I, O> {
    private EventType<I, O> eventType;

    public static <I, O> WithEvent<I, O> onEvent(EventType<I, O> eventType) {
      var b = new WithEvent<I, O>();
      b.eventType = eventType;
      return b;
    }

    public WithToState<I, O> to(State toState) {
      return new WithToState<>(new ModelContext<>(false, toState, eventType, List.of(), List.of(), List.of(), null));
    }

    public WithToState<I, O> toSelf() {
      return to(null);
    }

  }

  public static final class WithToState<I, O> {

    private final ModelContext<I, O> modelContext;

    public WithToState(ModelContext<I, O> modelContext) {
      this.modelContext = modelContext;
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(BiFunction<I, EventLog, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          initialChangeContext -> initialChangeContext.flatMap(c -> Mono.just(new AssembledChangeContext<>(
                  c,
                  assembler.apply(c.stepOutput(), c.log())
              )
          ))
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(Function<TransitionContext<I>, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          initialChangeContext -> initialChangeContext.map(i -> new AssembledChangeContext<>(i, assembler.apply(i)))
      );
    }

    public TransitionModelBuilder<I, I, O> assembleInput() {
      return new TransitionModelBuilder<>(
          modelContext,
          initialChangeContext -> initialChangeContext.map(i -> new AssembledChangeContext<>(i, i.input()))
      );
    }

    public <I1, O1> WithEventType<I, Void, O, I1, O1> trigger(EventType<I1, O1> eventType) {
      return new WithEventType<>(
          new TransitionModelBuilder<>(
              modelContext,
              initialChangeContext -> initialChangeContext.map(i -> new AssembledChangeContext<>(i, null))
          ),
          eventType
      );
    }

    public TransitionModel<I, O> output() {
      return new TransitionModelBuilder<>(
          modelContext,
          initialChangeContext -> initialChangeContext.map(cc -> new AssembledChangeContext<>(cc, null))
      ).output();
    }

  }

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InitialChangeContext<I>>, Mono<ChangeContext<T>>> builderFunction;

  private TransitionModelBuilder(
      ModelContext<I, O> modelContext,
      Function<Mono<InitialChangeContext<I>>, Mono<ChangeContext<T>>> builderFunction
  ) {
    this.modelContext = modelContext;
    this.builderFunction = builderFunction;
  }

  public record WithEventType<I, T, O, I1, O1>(TransitionModelBuilder<I, T, O> builder, EventType<I1, O1> eventType) {

    public record WithEventTypeAndData<I, T, O, I1, O1>(WithEventType<I, T, O, I1, O1> eventType, Function<T, I1> dataAdapter) {
      public WithEntity<I, T, O, I1, O1> on(EntityModel entityModel) {
        return new WithEntity<>(this, entityModel);
      }
    }

    public record WithIdentifier<I, T, O, I1, O1>(WithEntity<I, T, O, I1, O1> entity, List<Function<T, ? extends EntitySelector>> entitySelectors) {

      public <I2, O2> WithEventType<I, Tuple2<T, ProcessResult<O1>>, O, I2, O2> trigger(EventType<I2, O2> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public WithFilter<I, Tuple2<T, ProcessResult<O1>>, O> when(Predicate<Tuple2<T, ProcessResult<O1>>> filter) {
        return new WithFilter<>(complete(), filter);
      }

      public <T1> TransitionModelBuilder<I, Tuple2<Tuple2<T, ProcessResult<O1>>, IdentityResult<T1>>, O> newIdentifier(
          SecondaryIdModel<T1> model,
          Function<Tuple2<T, ProcessResult<O1>>, T1> data
      ) {
        return complete().newIdentifier(model, data);
      }

      public TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
        return complete().reversible(reverseModel);
      }

      public TransitionModel<I, O> output(Function<Tuple2<T, ProcessResult<O1>>, O> f) {
        return complete().output(f);
      }

      public TransitionModel<I, O> output() {
        return complete().output();
      }

      private EventTrigger<T, I1, O1> completeTrigger() {
        return new EventTrigger<>(
            new EventSpec<>(this.entity.eventTypeAndData.eventType.eventType, this.entity.eventTypeAndData.dataAdapter),
            this.entitySelectors,
            this.entity.entityModel,
            false // TODO: Support create entity
        );
      }

      private TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> complete() {
        return this.entity.eventTypeAndData.eventType.builder.trigger(completeTrigger());
      }

      public WithIdentifier<I, T, O, I1, O1> identifiedBy(Function<T, ? extends EntitySelector> entitySelector) {
        return new WithIdentifier<>(entity, List.of(entitySelector));
      }

    }

    public record WithEntity<I, T, O, I1, O1>(WithEventTypeAndData<I, T, O, I1, O1> eventTypeAndData, EntityModel entityModel) {

      @SafeVarargs
      public final TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> identifiedBy(Function<T, ? extends EntitySelector>... entitySelector) {
        return new WithIdentifier<>(this, List.of(entitySelector)).complete();
      }

    }

    public WithEventTypeAndData<I, T, O, I1, O1> with(Function<T, I1> dataAdapter) {
      return new WithEventTypeAndData<>(this, dataAdapter);
    }

    public WithEntity<I, T, O, I1, O1> on(EntityModel entityModel) {
      return new WithEntity<>(new WithEventTypeAndData<>(this, _ -> null), entityModel);
    }

  }

  public <I1, O1> WithEventType<I, T, O, I1, O1> trigger(EventType<I1, O1> eventType) {
    return new WithEventType<>(this, eventType);
  }

  public record WithAction<I, T, O, I1>(TransitionModelBuilder<I, T, O> builder, Action<I1> action) {

    public TransitionModelBuilder<I, T, O> with(Function<T, I1> dataAdapter) {
      var modelContext = new ModelContext<>(
          this.builder.modelContext.reversal,
          this.builder.modelContext.to,
          this.builder.modelContext.eventType,
          this.builder.modelContext.triggers,
          join(this.builder.modelContext.actions, action),
          this.builder.modelContext.filters,
          this.builder.modelContext.reverseModel
      );
      return new TransitionModelBuilder<>(
          modelContext,
          builder.builderFunction.andThen(c -> c.map(d -> new ActionChangeContext<>(
              d,
              new ActionTrigger<>(
                  action,
                  dataAdapter.apply(d.stepOutput()),
                  d.initialChangeContext().log()
              )
          )))
      );
    }

  }

  public <I1> WithAction<I, T, O, I1> trigger(Action<I1> action) {
    return new WithAction<>(this, action);
  }

  public TransitionModelBuilder<I, T, O> when(Map<Predicate<T>, TransitionModel<T, ?>> choices) {
    var builder = this;
    for (var entry : choices.entrySet()) {
      builder = builder.when(entry.getKey()).then(entry.getValue());
    }
    return builder;
  }

  public <I1> TransitionModelBuilder<I, T, O> choice(List<GuardedTransition<T, I1, ?>> guardedTransitions, Function<T, I1> dataAdapter) {
    TransitionModelBuilder<I, T, O> builder = this;
    for (var guardedTransition : guardedTransitions) {
      builder = builder.when(guardedTransition, dataAdapter);
    }
    return builder;
  }

  public TransitionModelBuilder<I, T, O> choice(List<GuardedTransition<T, T, ?>> guardedTransitions) {
    TransitionModelBuilder<I, T, O> builder = this;
    for (var guardedTransition : guardedTransitions) {
      builder = builder.when(guardedTransition);
    }
    return builder;
  }

  public <I1, O1> TransitionModelBuilder<I, T, O> when(GuardedTransition<T, I1, O1> guardedTransition, Function<T, I1> dataAdapter) {
    return when(guardedTransition.guard()).then(guardedTransition.then(), dataAdapter);
  }

  public <O1> TransitionModelBuilder<I, T, O> when(GuardedTransition<T, T, O1> guardedTransition) {
    return when(guardedTransition.guard()).then(guardedTransition.then());
  }

  public WithFilter<I, T, O> when(Predicate<T> filter) {
    return new WithFilter<>(this, filter);
  }

  public <O1> TransitionModel<I, O> otherwise(TransitionModel<T, O1> transitionModel) {
    return new WithFilter<>(this, _ -> true).then(transitionModel).complete();
  }

  public <I1, O1> TransitionModel<I, O> otherwise(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
    return new WithFilter<>(this, _ -> true).then(transitionModel, dataAdapter).complete();
  }

  public record Filter<T, I1, O1>(Predicate<T> predicate, Alternative<T, I1, O1> alternative) {}

  public static final class WithFilter<I, T, O> {

    public record Alternative<T, I1, O1>(Predicate<T> predicate, TransitionModel<I1, O1> model, Function<T, I1> dataAdapter) {
      public static <T, I1, O1> Alternative<T, I1, O1> then(Function<T, I1> dataAdapter, TransitionModel<I1, O1> model) {
        return new Alternative<>(_ -> true, model, dataAdapter);
      }
      public static <T, O1> Alternative<T, T, O1> then(TransitionModel<T, O1> model) {
        return new Alternative<>(_ -> true, model, Function.identity());
      }
    }

    private final TransitionModelBuilder<I, T, O> builder;
    private final Predicate<T> predicate;

    public WithFilter(TransitionModelBuilder<I, T, O> builder, Predicate<T> predicate) {
      this.builder = builder;
      this.predicate = predicate;
    }

    public <O1> TransitionModelBuilder<I, T, O> then(TransitionModel<T, O1> transitionModel) {
      return then(new Alternative<>(_ -> true, transitionModel, Function.identity()));
    }

    public <I1, O1> TransitionModelBuilder<I, T, O> then(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return then(new Alternative<>(_ -> true, transitionModel, dataAdapter));
    }

    public <I1, O1> TransitionModelBuilder<I, T, O> then(Alternative<T, I1, O1> alternative) {
      return complete(new Filter<>(this.predicate, alternative));
    }

    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, transitionModel, dataAdapter)));
    }

    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, transitionModel, dataAdapter)));
    }

    public <O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, TransitionModel<String, O1> transitionModel, String eventInput) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, transitionModel, _ -> eventInput)));
    }

    public <O1> TransitionModelBuilder<I, T, O> orElse(TransitionModel<String, O1> transitionModel, String eventInput) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, transitionModel, _ -> eventInput)));
    }

    public <I1, O1> TransitionModelBuilder<I, T, O> complete(Filter<T, I1, O1> filter) {
      var modelContext = new ModelContext<>(
          this.builder.modelContext.reversal,
          this.builder.modelContext.to,
          this.builder.modelContext.eventType,
          this.builder.modelContext.triggers,
          this.builder.modelContext.actions,
          join(this.builder.modelContext.filters, filter),
          this.builder.modelContext.reverseModel
      );
      return new TransitionModelBuilder<>(
          modelContext,
          this.builder.builderFunction.andThen(changeContext -> changeContext
              .flatMap(c -> {
                if (c instanceof ChoiceChangeContext) {
                  return Mono.just(c);
                }
                if (!filter.predicate().test(c.stepOutput())) {
                  return Mono.just(c);
                }
                return filter.alternative().model().calculate(
                        new InitialChangeContext<>(
                            null,
                            c,
                            filter.alternative().model(),
                            c.initialChangeContext().from(),
                            c.initialChangeContext().eventNumber(),
                            c.initialChangeContext().log(),
                            c.initialChangeContext().timestamp(),
                            c.initialChangeContext().correlationId(),
                            filter.alternative().dataAdapter().apply(c.stepOutput()),
                            c.initialChangeContext().stateMachine(),
                            List.of()
                        )
                    ).map(alternativeOutput -> new ChoiceChangeContext<>(alternativeOutput, c));
              })
          )
      );
    }

  }

  public static <T> TransitionModelBuilder<Data, T, Data> assemble(BiFunction<EventLog, EventType<Data, Data>, T> assembler) {
    return assembleReactive(assembler.andThen(Mono::just));
  }

  public static <T> TransitionModelBuilder<Data, T, Data> assembleReactive(BiFunction<EventLog, EventType<Data, Data>, Mono<T>> assembler) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(true /* !! */, null, BuiltinEventTypes.Rollback, List.of(), List.of(), List.of(), null),
        initialChangeContext -> initialChangeContext.flatMap(
            i -> {
              // TODO
              EventType<Data, Data> rollbackEventType = (EventType<Data, Data>)i.transitionModel().eventType();
              if (i.previous() != null && i.previous() instanceof ChangeContext.InitialChangeContext<?> pi)
                rollbackEventType = (EventType<Data, Data>)pi.transitionModel().eventType();
              return assembler.apply(
                  i.log(),
                  rollbackEventType
              ).map(assembled -> new AssembledChangeContext<>(i, assembled));
            }
        )
    );
  }

  public <T1> TransitionModelBuilder<I, Tuple2<T, IdentityResult<T1>>, O> newIdentifierInGroup(SecondaryIdModel<T1> model, Function<T, Object> data) {
    return new TransitionModelBuilder<>(
        modelContext,
        builderFunction.andThen(changeContext -> changeContext.flatMap(c ->
            c.initialChangeContext().stateMachine.next(model, data.apply(c.stepOutput()))
                .map(id -> new IdentityChangeContext<>(
                    c.initialChangeContext().log().entityModel(),
                    c.initialChangeContext().log().entityId(),
                    c,
                    IdentityResult.accepted(id)
                ))
                .map(icc -> new CombinedChangeContext<>(icc, c))
        ))
    );
  }

  public <T1> TransitionModelBuilder<I, Tuple2<T, IdentityResult<T1>>, O> newIdentifier(SecondaryIdModel<T1> model, Function<T, T1> data) {
    return new TransitionModelBuilder<>(
        modelContext,
        builderFunction.andThen(changeContext -> changeContext.map(c ->
                new CombinedChangeContext<>(
                    new IdentityChangeContext<>(
                        c.initialChangeContext().log().entityModel(),
                        c.initialChangeContext().log().entityId(),
                        c,
                        Optional.of(new SecondaryId<>(model, data.apply(c.stepOutput())))
                            .map(id -> {
                              IdentityResult<T1> r = c.firstInitialChangeContext().identityResult(id);
                              if (r != null) {
                                System.out.println("Found predefined IdentityResult");
                                return r;
                              }
                              return IdentityResult.accepted(id);
                            }).get()
                    ),
                    c
                )
            )
        )
    );
  }

  public TransitionModelBuilder<I, T, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            modelContext.triggers,
            modelContext.actions,
            modelContext.filters,
            reverseModel.complete()
        ),
        builderFunction
    );
  }

  private <I1, O1> TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> trigger(EventTrigger<T, I1, O1> eventTrigger) {
    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<Tuple2<T, ProcessResult<O1>>>>> f =
        changeContext -> changeContext
            .flatMap(c -> c.initialChangeContext().stateMachine().calculateTriggeredEvent(eventTrigger, c, c.stepOutput(), false)
                    .onErrorResume(
                        CircularChange.class,
                        e -> Mono.just(new PendingChangeContext<>(
                            c,
                            ProcessResult.pending(eventTrigger, c.stepOutput(), e)
                        ))
                    )
                    .onErrorResume(
                        RejectedEvent.class,
                        e -> Mono.just(new RejectedChangeContext<>(
                            c,
                            ProcessResult.rejected(eventTrigger.eventSpec().eventType(), e)
                        ))
                    )
                    .onErrorResume(
                        UnknownEntity.class,
                        e -> Mono.just(new UnknownIdChangeContext<>(
                            c,
                            ProcessResult.unknownId(eventTrigger.eventSpec().eventType(), e)
                        ))
                    )
                .map(output -> new TriggerChangeContext<>(output, c))
//                .contextWrite(ctx -> {
//                  EventLog eventLog = c.initialChangeContext().log();
//                  if (modelContext.eventType() == null) return ctx;
//                  System.out.println("Session entity id for " + eventLog.entityModel().name() + ": " + eventLog.entityId().value());
//                  return ctx.put(eventLog.entityModel(), eventLog.entityId());
//                })
            );
    var eventTriggers = join(modelContext.triggers, eventTrigger);
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            eventTriggers,
            modelContext.actions,
            modelContext.filters,
            modelContext.reverseModel
        ),
        builderFunction.andThen(f)
    );
  }

  private static <E> List<E> join(List<E> list, E element) {
    var l = new ArrayList<E>(list.size() + 1);
    l.addAll(list);
    l.add(element);
    return unmodifiableList(l);
  }

  public TransitionModel<I, O> output(Function<T, O> f) {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new OutputChangeContext<>(
            b,
            ProcessResult.accepted(
                new Event<>(
                    b.initialChangeContext().log().entityId().value(),
                    b.initialChangeContext().log().lastEventNumber() + 1,
                    modelContext.eventType(),
                    b.initialChangeContext().timestamp(),
                    f.apply(b.stepOutput())
                ),
                b.initialChangeContext().log().entityModel(),
                modelContext.to() != null && modelContext.to().timeout() != State.NEVER_TIMEOUT ? modelContext.to().timeout().duration() : null
            )
        )))
    );
  }

  private TransitionModel<I, O> complete() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> {
//              if (!(b instanceof ChoiceChangeContext) || !(b.previous() instanceof OutputChangeContext))
//                throw new IllegalStateException("Expected current ChangeContext to be ChoiceChangeContext (is " + b.getClass().getSimpleName() + ") or previous ChangeContext to be OutputChangeContext (is " + b.previous().getClass().getSimpleName() + ")");
              return new OutputChangeContext<>(
                  b,
                  ProcessResult.completed(
                      ((OutputChangeContext<?>)b.previous()).stepOutput(),
                      b.initialChangeContext().log().entityId(),
                      b.initialChangeContext().eventNumber(),
                      b.initialChangeContext().log().entityModel()
                  )
              );
            }
        ))
    );
  }

  public TransitionModel<I, O> output() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new OutputChangeContext<>(
            b,
            ProcessResult.accepted(
                new Event<>(
                    b.initialChangeContext().log().entityId().value(),
                    b.initialChangeContext().log().lastEventNumber() + 1,
                    modelContext.eventType(),
                    b.initialChangeContext().timestamp()
                ),
                b.initialChangeContext().log().entityModel(),
                modelContext.to() != null && modelContext.to().timeout() != State.NEVER_TIMEOUT ? modelContext.to().timeout().duration() : null
            )
        )))
    );
  }

  public static class TransitionModel<I, O> {

    private final ModelContext<I, O> modelContext;
    private final Function<Mono<InitialChangeContext<I>>, Mono<OutputChangeContext<O>>> chain;

    @Override
    public int hashCode() {
      return Objects.hash(modelContext.eventType, modelContext.to);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) return true;
      if (obj instanceof TransitionModel<?, ?> other) {
        return Objects.equals(this.modelContext.to, other.modelContext.to)
            && Objects.equals(this.modelContext.eventType, other.modelContext.eventType);
      }
      return false;
    }

    @Override
    public String toString() {
      return String.format("onEvent(%s).to(%s)", (modelContext.eventType != null ? modelContext.eventType.name() : "N/A") , modelContext.to != null ? modelContext.to : "<self>");
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
      log(modelContext, "calculate [" + eventType().name() + "] on [" + initialChangeContext.log().entityModel().name() + "]");
      Mono<OutputChangeContext<O>> output = chain.apply(Mono.just(initialChangeContext));
//      System.out.println("Calculate succeeded");
//      if (eventType().name().equals("Response")) {
//        System.out.println("Handling output for response");
//        output = output.doOnNext(c -> System.out.println("Got output for response"));
//        output = output.doOnError(c -> System.out.println("Got error for response"));
//        output = output.doOnSuccess(c -> System.out.println("Got success for response"));
//        output.subscribe();
//
//      }
      return output;
    }

    private Mono<OutputChangeContext<O>> calculateReverse(Mono<InitialChangeContext<I>> initial) {
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
  }

  static void log(ModelContext<?, ?> modelContext, String text) {
    System.out.println(ZonedDateTime.now().toString() + ": " + modelContext.description() + ": " + text);
  }

}
