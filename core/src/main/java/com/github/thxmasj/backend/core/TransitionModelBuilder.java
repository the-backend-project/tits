package com.github.thxmasj.backend.core;

import static java.util.Collections.unmodifiableList;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.EventTrigger.EventCondition;
import com.github.thxmasj.backend.core.EventTrigger.EventSpec;
import com.github.thxmasj.backend.core.StateMachine.CircularChange;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.TransitionStep.ActionStep;
import com.github.thxmasj.backend.core.TransitionStep.AssembleStep;
import com.github.thxmasj.backend.core.TransitionStep.ChoiceStep;
import com.github.thxmasj.backend.core.TransitionStep.ConditionalTriggerStep;
import com.github.thxmasj.backend.core.TransitionStep.DelayedTriggerStep;
import com.github.thxmasj.backend.core.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.TransitionStep.PendingStep;
import com.github.thxmasj.backend.core.TransitionStep.RejectedStep;
import com.github.thxmasj.backend.core.TransitionStep.TriggerStep;
import com.github.thxmasj.backend.core.TransitionStep.UnknownIdStep;
import com.github.thxmasj.backend.core.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
      return String.format("%s -> %s", eventType.name(), to != null ? to.name() : "<self>");
    }

  }

  public interface TransitionContext<I> {
    EventReference triggerEvent();
    State from();
    ZonedDateTime timestamp();
    String correlationId();
    EventLog log();
    I input();
    default EventReference eventReference() {
      return new EventReference(log().entityId(), log().lastEventNumber() + 1);
    }

  }


  public static TransitionModel<Void, State> statusOn() {
    return new TransitionModel<>(
        new ModelContext<>(false, null, BuiltinEventTypes.StatusLookedUp, List.of(), List.of(), List.of(), null),
        inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new OutputStep<>(
            i,
            ProcessResult.accepted(
                new Event<>(
                    i.log().entityId(),
                    i.log().lastEventNumber() + 1,
                    BuiltinEventTypes.StatusLookedUp,
                    i.timestamp(),
                    i.from()
                ),
                i.log().entityModel(),
                i.triggerType(),
                i.triggerId()
            )
        ))))
    );
  }

  public static TransitionModel<Data, Data> rollbackOn(EventType<Data, Data> eventType) {
    return new TransitionModel<>(
        new ModelContext<>(false, null, eventType, List.of(), List.of(), List.of(), null),
        inputStep -> inputStep.flatMap(i -> {
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
            int invalidRollbackTo = actualRollbackTo;
            return step(i, () -> Mono.just(new OutputStep<>(
                i,
                ProcessResult.rejected(
                    eventType,
                    new RejectedEvent(
                        eventType,
                        i.log().entityModel(),
                        i.log().entityId(),
                        i.from(),
                        invalidRollbackTo
                    )
                )
            )));
          }
          EventLog log = i.log();
          var effectiveEvents = log.effectiveEvents();
          List<Event<?>> eventsToRollback = effectiveEvents.subList(actualRollbackTo, effectiveEvents.size());
          Traverser traverser = i.stateMachine().traverser(log.entityModel());
          Mono<TransitionStep<?>> currentContext = Mono.just(i);
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
                  step(c, () -> Mono.just(new InputStep<>(
                      null,
                      c,
                      null,
                      tm.reverseModel(),
                      i.from(),
                      event.eventNumber(),
                      i.log(),
                      i.timestamp(),
                      i.correlationId(),
                      i.stepOutput(),
                      i.stateMachine(),
                      i.calculation()
                  )))
              ));
            }
          }
          return currentContext.flatMap(c -> step(c, () -> Mono.just(new OutputStep<>(
              c,
              ProcessResult.accepted(
                  new Event<>(
                      i.log().entityId(),
                      i.log().lastEventNumber() + 1,
                      eventType,
                      i.timestamp(),
                      i.stepOutput()
                  ),
                  i.log().entityModel(),
                  i.triggerType(),
                  i.triggerId()
              )
          ))));
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
          inputStep -> inputStep.flatMap(c -> step(c, () -> Mono.just(new AssembleStep<>(
                  c,
                  assembler.apply(c.stepOutput(), c.log())
              )
          )))
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(Function<TransitionContext<I>, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, assembler.apply(i)))))
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assembleReactive(Function<TransitionContext<I>, Mono<T>> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> assembler.apply(i).map(t -> new AssembleStep<>(i, t))))
      );
    }

    public TransitionModelBuilder<I, I, O> assembleInput() {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, i.input()))))
      );
    }

    public <I1, O1> WithEventType<I, Void, O, I1, O1> trigger(EventType<I1, O1> eventType) {
      return new WithEventType<>(
          new TransitionModelBuilder<>(
              modelContext,
              inputStep -> inputStep.flatMap(i -> step(i, () -> Mono.just(new AssembleStep<>(i, null))))
          ),
          eventType
      );
    }

    public TransitionModel<I, O> output() {
      return new TransitionModelBuilder<>(
          modelContext,
          inputStep -> inputStep.flatMap(cc -> step(cc, () -> Mono.just(new AssembleStep<>(cc, null))))
      ).output();
    }

  }

  private final ModelContext<I, O> modelContext;
  private final Function<Mono<InputStep<I>>, Mono<TransitionStep<T>>> builderFunction;

  private TransitionModelBuilder(
      ModelContext<I, O> modelContext,
      Function<Mono<InputStep<I>>, Mono<TransitionStep<T>>> builderFunction
  ) {
    this.modelContext = modelContext;
    this.builderFunction = builderFunction;
  }

  public record WithEventType<I, T, O, I1, O1>(TransitionModelBuilder<I, T, O> builder, EventType<I1, O1> eventType) {

    public record WithCondition<I, T, O, I1, O1>(
        WithEventType<I, T, O, I1, O1> eventType,
        EventType<?, ?> referenceType,
        Function<T, UUID> indexKey
    ) {

      /// The condition is met when the last event of every referenced entity is one of the given types.
      public ConditionalWithEventType<I, T, O, I1, O1> is(EventType<?, ?>... lastTypes) {
        if (lastTypes.length == 0 || lastTypes.length > 10)
          throw new IllegalArgumentException("Between 1 and 10 last event types must be given");
        return new ConditionalWithEventType<>(
            eventType.builder(),
            eventType.eventType(),
            new EventCondition<>(referenceType, indexKey, List.of(lastTypes))
        );
      }

    }

    public record ConditionalWithEventType<I, T, O, I1, O1>(
        TransitionModelBuilder<I, T, O> builder,
        EventType<I1, O1> eventType,
        EventCondition<T> condition
    ) {

      public ConditionalWithEventTypeAndData<I, T, O, I1, O1> with(Function<T, I1> dataAdapter) {
        return new ConditionalWithEventTypeAndData<>(this, dataAdapter, null);
      }

      /// The data of the event is created reactively. It is created when the trigger is stored, not when its condition is
      /// met.
      public ConditionalWithEventTypeAndData<I, T, O, I1, O1> withReactive(Function<T, Mono<I1>> dataAdapter) {
        return new ConditionalWithEventTypeAndData<>(this, createdReactively(eventType), dataAdapter);
      }

    }

    public record ConditionalWithEventTypeAndData<I, T, O, I1, O1>(
        ConditionalWithEventType<I, T, O, I1, O1> eventType,
        Function<T, I1> dataAdapter,
        Function<T, Mono<I1>> reactiveDataAdapter
    ) {

      public ConditionalWithEntity<I, T, O, I1, O1> on(EntityModel entityModel) {
        return new ConditionalWithEntity<>(this, entityModel);
      }

    }

    public record ConditionalWithEntity<I, T, O, I1, O1>(ConditionalWithEventTypeAndData<I, T, O, I1, O1> eventTypeAndData, EntityModel entityModel) {

      @SafeVarargs
      public final TransitionModelBuilder<I, T, O> identifiedBy(Function<T, ? extends EntitySelector>... entitySelector) {
        var eventType = eventTypeAndData.eventType();
        return eventType.builder().triggerConditional(
            new EventTrigger<>(
                new EventSpec<>(eventType.eventType(), eventTypeAndData.dataAdapter()),
                List.of(entitySelector),
                entityModel,
                false,
                eventType.condition()
            ),
            eventTypeAndData.reactiveDataAdapter()
        );
      }

    }

    public record WithEventTypeAndData<I, T, O, I1, O1>(
        WithEventType<I, T, O, I1, O1> eventType,
        Function<T, I1> dataAdapter,
        Function<T, Mono<I1>> reactiveDataAdapter
    ) {

      public WithEventTypeAndData(WithEventType<I, T, O, I1, O1> eventType, Function<T, I1> dataAdapter) {
        this(eventType, dataAdapter, null);
      }

      public WithEntity<I, T, O, I1, O1> on(EntityModel entityModel) {
        return new WithEntity<>(this, entityModel);
      }

      public TransitionModelBuilder<I, T, O> after(Function<T, Duration> delay) {
        if (reactiveDataAdapter != null)
          throw new UnsupportedOperationException("A delayed event can't have its data created reactively");
        return this.eventType.builder.triggerDelayed(this.eventType.eventType(), this.dataAdapter, delay);
      }

    }

    public record WithIdentifier<I, T, O, I1, O1>(WithEntity<I, T, O, I1, O1> entity, List<Function<T, ? extends EntitySelector>> entitySelectors) {

      public <I2, O2> WithEventType<I, Tuple2<T, ProcessResult<O1>>, O, I2, O2> trigger(EventType<I2, O2> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public WithFilter<I, Tuple2<T, ProcessResult<O1>>, O> when(Predicate<Tuple2<T, ProcessResult<O1>>> filter) {
        return new WithFilter<>(complete(), filter);
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

      private TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> complete() {
        return this.entity.eventTypeAndData.eventType.builder.trigger(
            new EventTrigger<>(
                new EventSpec<>(
                    this.entity.eventTypeAndData.eventType.eventType,
                    this.entity.eventTypeAndData.dataAdapter
                ),
                this.entitySelectors,
                this.entity.entityModel,
                false // TODO: Support create entity
            ),
            this.entity.eventTypeAndData.reactiveDataAdapter
        );
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

    public TransitionModelBuilder<I, T, O> after(Function<T, Duration> delay) {
      return this.builder.triggerDelayed(eventType, _ -> null, delay);
    }

    public WithEventTypeAndData<I, T, O, I1, O1> with(Function<T, I1> dataAdapter) {
      return new WithEventTypeAndData<>(this, dataAdapter);
    }

    /// The data of the event is created reactively, e.g. when it needs a value from an external source. Data from the
    /// context of the transition is assembled in the assemble step.
    public WithEventTypeAndData<I, T, O, I1, O1> withReactive(Function<T, Mono<I1>> dataAdapter) {
      return new WithEventTypeAndData<>(this, createdReactively(eventType), dataAdapter);
    }

    public WithCondition<I, T, O, I1, O1> when(EventType<?, ?> referenceType, Function<T, UUID> indexKey) {
      return new WithCondition<>(this, referenceType, indexKey);
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
          builder.builderFunction.andThen(c -> c.flatMap(d -> step(d, () -> Mono.just(new ActionStep<>(
              d,
              new ActionTrigger<>(
                  action,
                  dataAdapter.apply(d.stepOutput()),
                  d.inputStep().log()
              )
          )))))
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

  public <I1> TransitionModelBuilder<I, T, O> choice(List<GuardedTransition<T, I1, ?>> guardedTransitions) {
    TransitionModelBuilder<I, T, O> builder = this;
    for (var guardedTransition : guardedTransitions) {
      builder = builder.when(guardedTransition);
    }
    return builder;
  }

  public <I1, O1> TransitionModelBuilder<I, T, O> when(GuardedTransition<T, I1, O1> guardedTransition) {
    return when(guardedTransition.guard()).then(guardedTransition.then(), guardedTransition.dataAdapter());
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
          this.builder.builderFunction.andThen(transitionStep -> transitionStep
              .flatMap(c -> {
                if (c instanceof ChoiceStep) {
                  return Mono.just(c);
                }
                return step(c, () -> Mono.just(alternative(filter, c))).flatMap(alternative -> alternative.isEmpty() ?
                    Mono.just(c) :
                    filter.alternative().model().calculate(alternative.get())
                        .flatMap(alternativeOutput -> step(c, () -> Mono.just(new ChoiceStep<>(alternativeOutput, c))))
                );
              })
          )
      );
    }

    /// The input step of the alternative of the filter, if the filter chooses it
    private static <T, I1, O1> Optional<InputStep<I1>> alternative(Filter<T, I1, O1> filter, TransitionStep<T> c) {
      if (!filter.predicate().test(c.stepOutput())) {
        return Optional.empty();
      }
      return Optional.of(new InputStep<>(
          null,
          c,
          null,
          filter.alternative().model(),
          c.inputStep().from(),
          c.inputStep().eventNumber(),
          c.inputStep().log(),
          c.inputStep().timestamp(),
          c.inputStep().correlationId(),
          filter.alternative().dataAdapter().apply(c.stepOutput()),
          c.inputStep().stateMachine(),
          c.inputStep().calculation(),
          c.inputStep().triggerType(),
          c.inputStep().triggerId()
      ));
    }

  }

  public static <T> TransitionModelBuilder<Data, T, Data> assemble(BiFunction<EventLog, EventType<Data, Data>, T> assembler) {
    return assembleReactive(assembler.andThen(Mono::just));
  }

  public static <T> TransitionModelBuilder<Data, T, Data> assembleReactive(BiFunction<EventLog, EventType<Data, Data>, Mono<T>> assembler) {
    return assembleReactiveWithContext((c, rollbackType) -> assembler.apply(c.log(), rollbackType));
  }

  /// A reverse model assembling its data from the context of the rolling back transition, e.g. its correlation id, and
  /// the type of the rollback.
  public static <T> TransitionModelBuilder<Data, T, Data> assembleWithContext(BiFunction<TransitionContext<Data>, EventType<Data, Data>, T> assembler) {
    return assembleReactiveWithContext(assembler.andThen(Mono::just));
  }

  private static <T> TransitionModelBuilder<Data, T, Data> assembleReactiveWithContext(
      BiFunction<TransitionContext<Data>, EventType<Data, Data>, Mono<T>> assembler
  ) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(true /* !! */, null, BuiltinEventTypes.EventsRolledBack, List.of(), List.of(), List.of(), null),
        inputStep -> inputStep.flatMap(
            i -> step(i, () -> {
              // TODO
              EventType<Data, Data> rollbackEventType = (EventType<Data, Data>)i.transitionModel().eventType();
              if (i.previous() != null && i.previous() instanceof InputStep<?> pi)
                rollbackEventType = (EventType<Data, Data>)pi.transitionModel().eventType();
              return assembler.apply(
                  i,
                  rollbackEventType
              ).map(assembled -> new AssembleStep<>(i, assembled));
            })
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

  private <I1, O1> TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> trigger(
      EventTrigger<T, I1, O1> eventTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter
  ) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<Tuple2<T, ProcessResult<O1>>>>> f =
        transitionStep -> transitionStep
            .flatMap(c -> step(c, () -> withData(eventTrigger, reactiveDataAdapter, c)).flatMap(t -> c.inputStep().stateMachine().calculateTriggeredEvent(
                        t,
                        c,
                        c.stepOutput(),
                        false,
                        new EventReference(
                            c.inputStep().log().entityId(),
                            c.inputStep().eventNumber()
                        )
                    )
                    .onErrorResume(
                        CircularChange.class,
                        e -> step(c, () -> Mono.just(new PendingStep<>(
                            c,
                            ProcessResult.pending(t, c.stepOutput(), e)
                        )))
                    )
                    .onErrorResume(
                        RejectedEvent.class,
                        e -> step(c, () -> Mono.just(new RejectedStep<>(
                            c,
                            ProcessResult.rejected(eventTrigger.eventSpec().eventType(), e)
                        )))
                    )
                    .onErrorResume(
                        UnknownEntity.class,
                        e -> step(c, () -> Mono.just(new UnknownIdStep<>(
                            c,
                            ProcessResult.unknownId(eventTrigger.eventSpec().eventType(), e)
                        )))
                    )
                .flatMap(output -> step(c, () -> Mono.just(new TriggerStep<>(output, c, eventTrigger))))
            ));
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

  private <I1, O1> TransitionModelBuilder<I, T, O> triggerDelayed(EventType<I1, O1> eventType, Function<T, I1> dataAdapter, Function<T, Duration> delay) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<T>>> f =
        transitionStep -> transitionStep
            .flatMap(c -> step(c, () -> Mono.just(new DelayedTriggerStep<>(
                c,
                new DelayedEvent<>(
                    c.inputStep().log().entityModel(),
                    c.inputStep().log().entityId(),
                    eventType,
                    c.inputStep().eventNumber() + 1,
                    dataAdapter.apply(c.stepOutput()),
                    c.inputStep().timestamp().plus(delay.apply(c.stepOutput()))
                )
            ))));
    return new TransitionModelBuilder<>(
        modelContext,
        builderFunction.andThen(f)
    );
  }

  private <I1, O1> TransitionModelBuilder<I, T, O> triggerConditional(
      EventTrigger<T, I1, O1> conditionalTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter
  ) {
    Function<Mono<TransitionStep<T>>, Mono<TransitionStep<T>>> f =
        transitionStep -> transitionStep
            // The event is always stored as a ConditionalTrigger. If its condition is already met it is fired right after
            // the triggering change is committed, otherwise it is fired by the poller once the condition is met.
            .flatMap(c -> step(c, () -> withData(conditionalTrigger, reactiveDataAdapter, c).map(eventTrigger -> {
              var condition = eventTrigger.condition();
              return new ConditionalTriggerStep<>(
                  c,
                  new ConditionalTrigger<>(
                      UUID.randomUUID(),
                      new EventReference(
                          c.inputStep().log().entityId(),
                          c.inputStep().eventNumber()
                      ),
                      eventTrigger.entityModel(),
                      conditionalEntityId(eventTrigger, c),
                      eventTrigger.eventSpec().eventType(),
                      eventTrigger.eventSpec().inputAdapter().apply(c.stepOutput()),
                      c.inputStep().timestamp(),
                      condition.referenceType(),
                      condition.lastTypes(),
                      condition.indexKey().apply(c.stepOutput()),
                      c.inputStep().correlationId()
                  )
              );
            })));
    var eventTriggers = join(modelContext.triggers, conditionalTrigger);
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

  /// The trigger with the data of its event created, by the reactive data adapter if given. As the result of a step,
  /// it has the data created once.
  private static <T, I1, O1> Mono<EventTrigger<T, I1, O1>> withData(
      EventTrigger<T, I1, O1> eventTrigger,
      Function<T, Mono<I1>> reactiveDataAdapter,
      TransitionStep<T> c
  ) {
    if (reactiveDataAdapter == null) {
      var inputAdapter = eventTrigger.eventSpec().inputAdapter();
      I1 data = inputAdapter != null ? inputAdapter.apply(c.stepOutput()) : null;
      return Mono.just(eventTrigger.withInputAdapter(_ -> data));
    }
    return reactiveDataAdapter.apply(c.stepOutput())
        .switchIfEmpty(Mono.error(() -> new IllegalStateException(
            "No data created for " + eventTrigger.eventSpec().eventType().name()
        )))
        .map(data -> eventTrigger.withInputAdapter(_ -> data));
  }

  /// The data adapter of an event whose data is created reactively, which is never applied.
  private static <T, I1> Function<T, I1> createdReactively(EventType<I1, ?> eventType) {
    return _ -> {
      throw new IllegalStateException("The data of " + eventType.name() + " is created reactively");
    };
  }

  private static <T> UUID conditionalEntityId(EventTrigger<T, ?, ?> eventTrigger, TransitionStep<T> c) {
    var selector = eventTrigger.entitySelectors().getFirst().apply(c.stepOutput());
    if (selector instanceof EntitySelector.ById byId) {
      return byId.id();
    }
    if (selector instanceof EntitySelector.ByIdFromSession
        && c.inputStep().log().entityModel().equals(eventTrigger.entityModel())) {
      return c.inputStep().log().entityId();
    }
    throw new UnsupportedOperationException("Conditional trigger requires the entity to be identified by id or by the session's entity");
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
        builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> Mono.just(new OutputStep<>(
            b,
            ProcessResult.accepted(
                new Event<>(
                    b.inputStep().log().entityId(),
                    b.inputStep().log().lastEventNumber() + 1,
                    modelContext.eventType(),
                    b.inputStep().timestamp(),
                    f.apply(b.stepOutput())
                ),
                b.inputStep().log().entityModel(),
                b.inputStep().triggerType(),
                b.inputStep().triggerId()
            )
        )))))
    );
  }

  private TransitionModel<I, O> complete() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> {
              if (!(b.previous() instanceof OutputStep) && !(b.previous() instanceof PendingStep)) {
                throw new IllegalStateException("Previous step should be output or pending");
              }
              return Mono.just(new OutputStep<>(
                  b,
                  ProcessResult.completed(
                      b.previous() instanceof OutputStep<?> o ? o.stepOutput() : (b.previous() instanceof PendingStep<?, ?, ?> p ? p.stepOutput() : null),
                      b.inputStep().log().entityId(),
                      b.inputStep().eventNumber(),
                      b.inputStep().log().entityModel()
                  )
              ));
            }
        )))
    );
  }

  public TransitionModel<I, O> output() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.flatMap(b -> step(b, () -> Mono.just(new OutputStep<>(
            b,
            ProcessResult.accepted(
                new Event<>(
                    b.inputStep().log().entityId(),
                    b.inputStep().log().lastEventNumber() + 1,
                    modelContext.eventType(),
                    b.inputStep().timestamp()
                ),
                b.inputStep().log().entityModel(),
                b.inputStep().triggerType(),
                b.inputStep().triggerId()
            )
        )))))
    );
  }


  /// The result of a step of the calculation the transition step is part of. Every step is taken like this, such that
  /// it is not executed again when the changes are calculated again from a later step.
  private static <R> Mono<R> step(TransitionStep<?> context, Supplier<Mono<R>> execution) {
    return context.inputStep().calculation().step(context, execution);
  }

  static void log(ModelContext<?, ?> modelContext, String text) {
    System.out.println(ZonedDateTime.now() + ": " + modelContext.description() + ": " + text);
  }

}
