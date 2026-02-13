package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

import com.github.thxmasj.statemachine.BasicEventType.Rollback;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.StateMachine.ChangeSet;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.Message;
import com.github.thxmasj.statemachine.message.Message.IncomingMessage;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
      //State from,
      State to,
      EventType<I, O> eventType,
      List<OutgoingRequestModel<?, ?>> outgoingRequests,
      OutgoingResponseModel<?, ?> outgoingResponse,
      List<Filter<?, ?, ?>> filters,
      List<ScheduledEvent<?, ?>> scheduledEvents,
      TransitionModel<Rollback.Data, Void> reverseModel
  ) {

    // TODO: Use different classes / hierarchy instead
    public ModelContext<I, O> reversalContext() {
      return new ModelContext<>(null, null, List.of(), null, List.of(), List.of(), null);
    }

    public boolean isReversal() {
      return to == null && eventType == null;
    }

    public String description() {
      return String.format("%s -> %s", eventType.name(), to);
    }

  }

  public record TransitionContext<I>(
      State from,
      ZonedDateTime timestamp,
      String correlationId,
      StateMachine stateMachine,
      InputEvent<I> input,
      EventLog log
  ) {

    public Mono<EventLog> log(SecondaryId secondaryId, EntityModel entityModel) {
      return stateMachine.log(secondaryId, entityModel);
    }

    public TransitionContext<I> withNewEvent(Event<?> newEvent) {
      return new TransitionContext<>(
          from,
          timestamp,
          correlationId,
          stateMachine,
          input,
          log.withNewEvent(newEvent)
      );
    }

  }

  public record ChangeContext<T>(
      TransitionContext<?> transitionContext,
      List<OutgoingRequest>  outgoingRequests,
      //OutgoingResponse outgoingResponse,
      List<SecondaryId> secondaryIds,
      List<ChangeSet<?>> nestedChanges,
      boolean filterUsed,
      T stepOutput
  ) {}

  private static <T> ArrayList<T> add(ArrayList<T> list, T element) {
    list.add(element);
    return list;
  }

  public static final class WithEvent<I, O> {
    private EventType<I, O> eventType;

    public static <I, O> WithEvent<I, O> onEvent(EventType<I, O> eventType) {
      var b = new WithEvent<I, O>();
      b.eventType = eventType;
      return b;
    }

//    public WithFromState<I, O> from(State fromState) {
//      return new WithFromState<>(eventType, fromState);
//    }

    public WithToState<I, O> to(State toState) {
      return new WithToState<>(eventType, toState);
    }

    public WithToState<I, O> toSelf() {
      return new WithToState<>(eventType, null);
    }

  }

  public static final class WithFromState<I, O> {

    private final EventType<I, O> eventType;
    private final State fromState;

    public WithFromState(EventType<I, O> eventType, State fromState) {
      this.eventType = eventType;
      this.fromState = fromState;
    }

    public WithToState<I, O> to(State toState) {
      return new WithToState<>(eventType, /*fromState, */toState);
    }

    public WithToState<I, O> toSelf() {
      return new WithToState<>(eventType, /*fromState, */fromState);
    }

  }

  public static final class WithToState<I, O> {

    private final ModelContext<I, O> modelContext;

    public WithToState(EventType<I, O> eventType, /*State fromState,*/ State toState) {
      this.modelContext = new ModelContext<>(
//          fromState,
          toState,
          eventType,
          new ArrayList<>(),
          null,
          new ArrayList<>(),
          new ArrayList<>(),
          null
      );
    }

    private <T> Mono<ChangeContext<T>> initialChangeContext(TransitionContext<I> transitionContext, T stepOutput) {
      return Mono.just(new ChangeContext<>(
          transitionContext,
          List.of(),
          //null,
          List.of(),
          List.of(),
          false,
          stepOutput
      ));
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(BiFunction<InputEvent<I>, EventLog, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          transitionContext -> initialChangeContext(
              transitionContext,
              assembler.apply(transitionContext.input(), transitionContext.log())
          )
      );
    }

    public <T> TransitionModelBuilder<I, T, O> assemble(Function<TransitionContext<I>, T> assembler) {
      return new TransitionModelBuilder<>(
          modelContext,
          transitionContext -> initialChangeContext(transitionContext, assembler.apply(transitionContext))
      );
    }

    public TransitionModelBuilder<I, I, O> assembleInput() {
      BiFunction<InputEvent<I>, EventLog, I> assembler = (input, _) -> input.data();
      return new TransitionModelBuilder<>(
          modelContext,
          transitionContext -> initialChangeContext(transitionContext, assembler.apply(transitionContext.input(), transitionContext.log()))
      );
    }

    public <I1, O1> WithEventType<I, Void, O, I1, O1> trigger(EventType<I1, O1> eventType) {
      return new WithEventType<>(
          new TransitionModelBuilder<>(modelContext, transitionContext -> initialChangeContext(transitionContext, null)),
          eventType
      );
    }

    public <T1> WithOutgoingRequest<I, Void, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
      return new WithOutgoingRequest<>(
          new TransitionModelBuilder<>(modelContext, transitionContext -> initialChangeContext(transitionContext, null)),
          outgoingRequestCreator
      );
    }

//    public <T1> WithOutgoingResponse<I, Void, O, T1> trigger(OutgoingResponseCreator<T1> outgoingResponseCreator) {
//      return new WithOutgoingResponse<>(
//          new TransitionModelBuilder<>(modelContext, tuple -> initialChangeContext(tuple.t1(), null)),
//          outgoingResponseCreator
//      );
//    }

    public TransitionModel<I, O> output() {
      return new TransitionModel<>(modelContext, transitionContext -> initialChangeContext(transitionContext, null));
    }

  }

  protected final ModelContext<I, O> modelContext;
  protected final Function<TransitionContext<I>, Mono<ChangeContext<T>>> builderFunction;

  private TransitionModelBuilder(
      ModelContext<I, O> modelContext,
      Function<TransitionContext<I>, Mono<ChangeContext<T>>> builderFunction
  ) {
    this.modelContext = modelContext;
    this.builderFunction = builderFunction;
  }

//  public record WithOutgoingResponse<I, T, O, T1>(TransitionModelBuilder<I, T, O> builder, OutgoingResponseCreator<T1> outgoingResponseCreator) {
//
//    public record WithOutgoingResponseAndData<I, T, O, T1>(
//        WithOutgoingResponse<I, T, O, T1> outgoingResponse,
//        Function<T, T1> dataAdapter
//    ) {
//
//      private TransitionModelBuilder<I, T, O> complete() {
//        return outgoingResponse.builder.trigger(
//            new OutgoingResponseModel<>(dataAdapter, null, outgoingResponse.outgoingResponseCreator())
//        );
//      }
//
//      public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Void, Void> reverseModel) {
//        return complete().reversible(reverseModel);
//      }
//
//      public TransitionModel<I, O> output(Function<T, O> f) {
//        return complete().output(f);
//      }
//
//      public TransitionModel<I, O> output() {
//        return complete().output();
//      }
//
//    }
//
//    public WithOutgoingResponseAndData<I, T, O, T1> with(Function<T, T1> dataAdapter) {
//      return new WithOutgoingResponseAndData<>(this, dataAdapter);
//    }
//
//  }

  public record WithOutgoingRequest<I, T, O, T1>(TransitionModelBuilder<I, T, O> builder, OutgoingRequestCreator<T1> outgoingRequestCreator) {

    public record WithOutgoingRequestAndData<I, T, O, T1>(
        WithOutgoingRequest<I, T, O, T1> outgoingRequest,
        Function<T, T1> dataAdapter
    ) {

      public WithOutgoingRequestAndDataAndQueue<I, T, O, T1> to(OutboxQueue to) {
        return new WithOutgoingRequestAndDataAndQueue<>(this, to);
      }

    }

    public static final class WithOutgoingRequestAndDataAndQueue<I, T, O, T1> {

      private final WithOutgoingRequestAndData<I, T, O, T1> base;
      private final OutboxQueue queue;
      private IncomingResponseValidator<?> responseValidator;
      private boolean guaranteed = false;
      private int retryTimes;
      private Duration retryInterval;

      public WithOutgoingRequestAndDataAndQueue(
          WithOutgoingRequestAndData<I, T, O, T1> base,
          OutboxQueue queue
      ) {
        this.base = base;
        this.queue = queue;
      }

      public WithOutgoingRequestAndDataAndQueue<I, T, O, T1> responseValidator(
          IncomingResponseValidator<?> responseValidator
      ) {
        this.responseValidator = responseValidator;
        return this;
      }

      public WithOutgoingRequestAndDataAndQueue<I, T, O, T1> guaranteed() {
        this.guaranteed = true;
        return this;
      }

      public WithOutgoingRequestAndDataAndQueue<I, T, O, T1> retry(int times, Duration interval) {
        this.retryTimes = times;
        this.retryInterval = interval;
        return this;
      }

      private TransitionModelBuilder<I, T, O> complete() {
        var requestModelBuilder = OutgoingRequestModel.Builder.request(
                base.dataAdapter(),
                base.outgoingRequest().outgoingRequestCreator()
            ).to(queue).responseValidator(responseValidator);
        if (guaranteed) requestModelBuilder = requestModelBuilder.guaranteed();
        if (retryTimes > 0) requestModelBuilder = requestModelBuilder.retry(retryTimes, retryInterval);
        return base.outgoingRequest().builder().trigger(requestModelBuilder.build());
      }

      public WithOutgoingRequestAndDataAndQueue<I, T, O, T1> to(OutboxQueue to) {
        return new WithOutgoingRequestAndDataAndQueue<>(new WithOutgoingRequestAndData<>(this.base.outgoingRequest(), (T _) -> null), to);
      }

      public <I1, O1> WithEventType<I, T, O, I1, O1> trigger(EventType<I1, O1> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public <T2> WithOutgoingRequest<I, T, O, T2> trigger(OutgoingRequestCreator<T2> outgoingRequestCreator) {
        return new WithOutgoingRequest<>(complete(), outgoingRequestCreator);
      }

//      public <T2> WithOutgoingResponse<I, T, O, T2> trigger(OutgoingResponseCreator<T2> outgoingResponseCreator) {
//        return new WithOutgoingResponse<>(complete(), outgoingResponseCreator);
//      }

      //      public <T2> TransitionModelBuilder<I, T, O> trigger(OutgoingResponseModel<T, T2> responseModel) {
//        return complete().trigger(responseModel);
//      }

      public TransitionModelBuilder<I, T, O> schedule(EventType<Void, ?> eventType, Duration deadline) {
        return complete().schedule(eventType, deadline);
      }

      public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Rollback.Data, Void> reverseModel) {
        return complete().reversible(reverseModel);
      }

      public TransitionModel<I, O> output(Function<T, O> f) {
        return complete().output(f);
      }

      public TransitionModel<I, O> output() {
        return complete().output();
      }

      public OutboxQueue queue() {return queue;}

    }

    public WithOutgoingRequestAndData<I, T, O, T1> with(Function<T, T1> dataAdapter) {
      return new WithOutgoingRequestAndData<>(this, dataAdapter);
    }

  }

  public record WithEventType<I, T, O, I1, O1>(TransitionModelBuilder<I, T, O> builder, EventType<I1, O1> eventType) {

    public record WithEventTypeAndData<I, T, O, I1, O1>(WithEventType<I, T, O, I1, O1> eventType, Function<T, I1> dataAdapter) {
      public WithEntity<I, T, O, I1, O1> on(EntityModel entityModel) {
        return new WithEntity<>(this, entityModel);
      }
    }

    public record WithIdentifier<I, T, O, I1, O1>(WithEntity<I, T, O, I1, O1> entity, ArrayList<EntitySelector<T>> entitySelectors) {

      public <I2, O2> WithEventType<I, Tuple2<T, EventReference<O1>>, O, I2, O2> trigger(EventType<I2, O2> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public <T1> WithOutgoingRequest<I, Tuple2<T, EventReference<O1>>, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
        return new WithOutgoingRequest<>(complete(), outgoingRequestCreator);
      }

      public <I2, O2> TransitionModelBuilder<I, Tuple2<T, EventReference<O1>>, O> schedule(EventType<I2, O2> eventType, Duration deadline) {
        return complete().schedule(eventType, deadline);
      }

      public WithFilter<I, Tuple2<T, EventReference<O1>>, O> when(Predicate<Tuple2<T, EventReference<O1>>> filter) {
        return new WithFilter<>(complete(), filter);
      }

//      public <T1> WithOutgoingResponse<I, Tuple2<T, O1>, O, T1> trigger(OutgoingResponseCreator<T1> outgoingResponseCreator) {
//        return new WithOutgoingResponse<>(complete(), outgoingResponseCreator);
//      }

      public TransitionModelBuilder<I, Tuple2<T, EventReference<O1>>, O> newIdentifier(SecondaryIdModel model, Function<Tuple2<T, EventReference<O1>>, Object> data) {
        return complete().newIdentifier(model, data);
      }

      public TransitionModelBuilder<I, Tuple2<T, EventReference<O1>>, O> reversible(TransitionModel<Rollback.Data, Void> reverseModel) {
        return complete().reversible(reverseModel);
      }

      public TransitionModel<I, O> output(Function<Tuple2<T, EventReference<O1>>, O> f) {
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

      private TransitionModelBuilder<I, Tuple2<T, EventReference<O1>>, O> complete() {
        return this.entity.eventTypeAndData.eventType.builder.trigger(completeTrigger());
      }

      public WithIdentifier<I, T, O, I1, O1> identifiedBy(EntitySelector<T> entitySelector) {
        return new WithIdentifier<>(entity, add(entitySelectors, entitySelector));
      }

    }

    public record WithEntity<I, T, O, I1, O1>(WithEventTypeAndData<I, T, O, I1, O1> eventTypeAndData, EntityModel entityModel) {
      public WithIdentifier<I, T, O, I1, O1> identifiedBy(EntitySelector<T> entitySelector) {
        return new WithIdentifier<>(this, new ArrayList<>(List.of(entitySelector)));
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

  public <T1> WithOutgoingRequest<I, T, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
    return new WithOutgoingRequest<>(this, outgoingRequestCreator);
  }

  public WithFilter<I, T, O> when(Predicate<T> filter) {
    return new WithFilter<>(this, filter);
  }

  public record Filter<T, I1, O1>(Predicate<T> predicate, Alternative<T, I1, O1> alternative) {}

  public static final class WithFilter<I, T, O> {

    public record Alternative<T, I1, O1>(Predicate<T> predicate, TransitionModel<I1, O1> model, Function<T, I1> dataAdapter) {}

    private final TransitionModelBuilder<I, T, O> builder;
    private final Predicate<T> predicate;

    public WithFilter(TransitionModelBuilder<I, T, O> builder, Predicate<T> predicate) {
      this.builder = builder;
      this.predicate = predicate;
    }

    public <O1> TransitionModelBuilder<I, T, O> then(TransitionModel<T, O1> transitionModel) {
      return complete(new Filter<>(this.predicate, new Alternative<>(_ -> true, transitionModel, Function.identity())));
    }

//    public <O1> TransitionModelBuilder<I, T, O> then(EventType<T, O1> eventType) {
//      return complete(new Filter<>(this.predicate, new Alternative<>(_ -> true, eventType, Function.identity())));
//    }

    public <I1, O1> TransitionModelBuilder<I, T, O> then(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return complete(new Filter<>(this.predicate, new Alternative<>(_ -> true, transitionModel, dataAdapter)));
    }

//    public <I1, O1> TransitionModelBuilder<I, T, O> then(EventType<I1, O1> eventType, Function<T, I1> dataAdapter) {
//      return complete(new Filter<>(this.predicate, new Alternative<>(_ -> true, eventType, dataAdapter)));
//    }

    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, transitionModel, dataAdapter)));
    }

//    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, EventType<I1, O1> eventType, Function<T, I1> dataAdapter) {
//      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, eventType, dataAdapter)));
//    }

    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(TransitionModel<I1, O1> transitionModel, Function<T, I1> dataAdapter) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, transitionModel, dataAdapter)));
    }

//    public <I1, O1> TransitionModelBuilder<I, T, O> orElse(EventType<I1, O1> eventType, Function<T, I1> dataAdapter) {
//      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, eventType, dataAdapter)));
//    }

    public <O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, TransitionModel<String, O1> transitionModel, String eventInput) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, transitionModel, _ -> eventInput)));
    }

//    public <O1> TransitionModelBuilder<I, T, O> orElse(Predicate<T> altPredicate, EventType<String, O1> eventType, String eventInput) {
//      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(altPredicate, eventType, _ -> eventInput)));
//    }

    public <O1> TransitionModelBuilder<I, T, O> orElse(TransitionModel<String, O1> transitionModel, String eventInput) {
      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, transitionModel, _ -> eventInput)));
    }

//    public <O1> TransitionModelBuilder<I, T, O> orElse(EventType<String, O1> eventType, String eventInput) {
//      return complete(new Filter<>(this.predicate.negate(), new Alternative<>(_ -> true, eventType, _ -> eventInput)));
//    }

    public <I1, O1> TransitionModelBuilder<I, T, O> complete(Filter<T, I1, O1> filter) {
      return new TransitionModelBuilder<>(
          new ModelContext<>(
              //this.builder.modelContext.from(),
              this.builder.modelContext.to(),
              this.builder.modelContext.eventType(),
              this.builder.modelContext.outgoingRequests(),
              this.builder.modelContext.outgoingResponse(),
              join(this.builder.modelContext.filters(), filter),
              this.builder.modelContext.scheduledEvents(),
              this.builder.modelContext.reverseModel()
          ),
          this.builder.builderFunction.andThen(changeContext -> changeContext
              .flatMap(c -> !c.filterUsed() && filter.predicate().test(c.stepOutput()) ?
                  filter.alternative().model().calculate(
                      c.transitionContext().from(),
                      null,
                      c.transitionContext().stateMachine(),
                      Clock.systemUTC(), // TODO
                      new InputEvent<>(filter.alternative().model().eventType(), filter.alternative().dataAdapter().apply(c.stepOutput())),
                      c.transitionContext().log(),
                      c.transitionContext().timestamp(),
                      c.transitionContext().correlationId()
                  )
//                  c.transitionContext().stateMachine().calculateForFilter(
//                      c.transitionContext().correlationId(),
//                      c.transitionContext().timestamp(),
//                      c.transitionContext().log(),
//                      filter.alternative().eventType(),
//                      filter.alternative().dataAdapter().apply(c.stepOutput())
//                  )
                  .map(change -> new ChangeContext<>(
                      c.transitionContext(),
                      c.outgoingRequests(),
                      //c.outgoingResponse(),
                      c.secondaryIds(),
                      join(c.nestedChanges(), change),
                      true,
                      c.stepOutput
                  )) : changeContext
              )
          )
      );
    }

  }

  public static <T> TransitionModelBuilder<Rollback.Data, T, Void> assemble(BiFunction<EventLog, EventType<Rollback.Data, ?>, T> assembler) {
    return assembleReactive(assembler.andThen(Mono::just));
  }

  public static <T> TransitionModelBuilder<Rollback.Data, T, Void> assembleReactive(BiFunction<EventLog, EventType<Rollback.Data, ?>, Mono<T>> assembler) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(/*null,*/ null, null, List.of(), null, List.of(), List.of(), null),
        transitionContext -> assembler.apply(transitionContext.log(), transitionContext.input().eventType())
            .map(assembled -> new ChangeContext<>(
                transitionContext,
                List.of(),
                //null,
                List.of(),
                List.of(),
                false,
                assembled
            ))
    );
  }

  public TransitionModelBuilder<I, T, O> newIdentifier(SecondaryIdModel model, Function<T, Object> data) {

    return new TransitionModelBuilder<>(
        new ModelContext<>(
            //modelContext.from(),
            modelContext.to(),
            modelContext.eventType(),
            modelContext.outgoingRequests(),
            modelContext.outgoingResponse(),
            modelContext.filters(),
            modelContext.scheduledEvents(),
            modelContext.reverseModel()
        ),
        builderFunction.andThen(changeContext -> changeContext.map(c -> new ChangeContext<>(
            c.transitionContext(),
            c.outgoingRequests(),
            //c.outgoingResponse(),
            join(c.secondaryIds(), new SecondaryId(model, requireNonNull(data, "data is null").apply(requireNonNull(c.stepOutput(), "step output is null for newIdentifier(" + model.name() + ") - transition: " + modelContext.eventType().name() + "->" + modelContext.to())))),
            c.nestedChanges(),
            c.filterUsed(),
            c.stepOutput
        )))
    );
  }

  private <I1, O1> TransitionModelBuilder<I, T, O> schedule(EventType<I1, O1> eventType, Duration deadline) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            //modelContext.from(),
            modelContext.to(),
            modelContext.eventType(),
            modelContext.outgoingRequests(),
            modelContext.outgoingResponse(),
            modelContext.filters(),
            join(modelContext.scheduledEvents(), new ScheduledEvent<>(eventType, deadline)),
            modelContext.reverseModel()
        ),
        builderFunction.andThen(changeContext -> changeContext.flatMap(c -> c.transitionContext().timestamp()
            .isAfter(c.transitionContext().log().effectiveEvents().getLast().timestamp().plus(deadline)) ?
            c.transitionContext().stateMachine().calculateChange(
                eventType,
                c.transitionContext().log(),
                c.transitionContext().timestamp(),
                c.transitionContext().correlationId()
            ).map(changeSet ->
                new ChangeContext<>(
                    c.transitionContext().withNewEvent(changeSet.changes().getLast().newEvent()),
                    c.outgoingRequests(),
                    //c.outgoingResponse(),
                    c.secondaryIds(),
                    join(c.nestedChanges(), changeSet),
                    c.filterUsed(),
                    c.stepOutput()
                )) :
            Mono.just(c)
        ))
    );
  }

  public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Rollback.Data, Void> reverseModel) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            //modelContext.from(),
            modelContext.to(),
            modelContext.eventType(),
            modelContext.outgoingRequests(),
            modelContext.outgoingResponse(),
            modelContext.filters(),
            modelContext.scheduledEvents(),
            reverseModel
        ),
        builderFunction
    );
  }

  public record EventReference<T>(EntityId entityId, Event<T> event) {}

  public <I1, O1> TransitionModelBuilder<I, Tuple2<T, EventReference<O1>>, O> trigger(EventTrigger<T, I1, O1> eventTrigger) {
    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<Tuple2<T, EventReference<O1>>>>> f =
        changeContext -> changeContext.flatMap(c -> c.transitionContext().stateMachine().calculateOnEvent(
                eventTrigger,
                c.stepOutput()
            )
            .map(reactor.util.function.Tuple2::getT1)
            .map(changeSet -> new ChangeContext<>(
                c.transitionContext(),
                c.outgoingRequests(),
                //c.outgoingResponse(),
                c.secondaryIds(),
                join(c.nestedChanges(), changeSet),
                c.filterUsed(),
                tuple(c.stepOutput, changeSet.output())
            )).contextWrite(ctx -> {
              EventLog eventLog = c.transitionContext.log();
              if (modelContext.eventType() == null)
                return ctx;
              //System.out.println("Putting on context " + eventLog.entityId());
              //return ctx.put(eventLog.entityId(), eventLog.withNewEvent(
              var sessionLog = eventLog.withNewEvent(new Event<>(eventLog.lastEventNumber() + 1, modelContext.eventType, Clock.systemUTC()));
              System.out.println(modelContext.description() + ": Putting event log on session for entity " + sessionLog.entityModel().name() + "/" + sessionLog.entityId().value() + ": " + sessionLog.events().stream().map(Event::typeName).collect(Collectors.joining(",")));
              return ctx.put(sessionLog.entityModel(), sessionLog);
            }));
    return new TransitionModelBuilder<>(modelContext, builderFunction.andThen(f));
  }

  private static <E> List<E> join(List<E> list, E element) {
    var l = new ArrayList<E>(list.size() + 1);
    l.addAll(list);
    l.add(element);
    return unmodifiableList(l);
  }

  public <T1> TransitionModelBuilder<I, T, O> trigger(OutgoingRequestModel<T, T1> requestModel) {
    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<T>>> f =
        c -> c.flatMap(changeContext -> changeContext.transitionContext().stateMachine().createOutgoingRequest(
                changeContext.transitionContext().timestamp(),
                new Entity(
                    changeContext.transitionContext().log().entityId(),
                    changeContext.transitionContext().log().secondaryIds(),
                    changeContext.transitionContext().log().entityModel()
                ),
                changeContext.stepOutput(),
                modelContext.eventType(),
                changeContext.transitionContext().log().lastEventNumber() + 1,
                // TODO: This assumes nested change with parent entity is always calculated before outgoing request. Model does not restrict to that.
                //       Refactor as the concept is weak anyways.
                changeContext.nestedChanges().stream().flatMap(nestedChange -> nestedChange.changes().stream()).toList(),
                requestModel,
                changeContext.transitionContext().correlationId()
                )
                .map(request -> new ChangeContext<>(
                    changeContext.transitionContext(),
                    join(changeContext.outgoingRequests(), request),
                    //changeContext.outgoingResponse(),
                    changeContext.secondaryIds(),
                    changeContext.nestedChanges(),
                    changeContext.filterUsed(),
                    changeContext.stepOutput()
                ))
        );
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.to(),
            modelContext.eventType(),
            join(modelContext.outgoingRequests(), requestModel),
            modelContext.outgoingResponse(),
            modelContext.filters(),
            modelContext.scheduledEvents(),
            modelContext.reverseModel()
        ),
        builderFunction.andThen(f)
    );
  }

//  public <T1> WithOutgoingResponse<I, T, O, T1> trigger(OutgoingResponseCreator<T1> outgoingResponseCreator) {
//    return new WithOutgoingResponse<>(this, outgoingResponseCreator);
//  }

//  public <T1> TransitionModelBuilder<I, T, O> trigger(OutgoingResponseModel<T, T1> responseModel) {
//    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<T>>> f =
//        stepContext -> stepContext.flatMap(c -> c.transitionContext().stateMachine().createOutgoingResponse(
//                    c.transitionContext().timestamp(),
//                    c.transitionContext().log().entityId(),
//                    c.stepOutput(),
//                    c.transitionContext().log().lastEventNumber() + 1,
//                    responseModel
//                )
//                .map(response -> new ChangeContext<>(
//                    c.transitionContext(),
//                    c.outgoingRequests(),
//                    response,
//                    c.secondaryIds(),
//                    c.nestedChanges(),
//                    c.filterUsed(),
//                    c.stepOutput()
//                ))
//        );
//    return new TransitionModelBuilder<>(
//        new ModelContext<>(
//            //modelContext.from(),
//            modelContext.to(),
//            modelContext.eventType(),
//            modelContext.outgoingRequests(),
//            responseModel,
//            modelContext.filters(),
//            modelContext.scheduledEvents(),
//            modelContext.reverseModel()
//        ),
//        builderFunction.andThen(f)
//    );
//  }

  public TransitionModel<I, O> output(Function<T, O> f) {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new ChangeContext<>(
            b.transitionContext(),
            b.outgoingRequests(),
            //b.outgoingResponse(),
            b.secondaryIds(),
            b.nestedChanges(),
            b.filterUsed(),
            f.apply(b.stepOutput())
        )))
    );
  }

  public TransitionModel<I, O> output() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new ChangeContext<>(
            b.transitionContext(),
            b.outgoingRequests(),
            //b.outgoingResponse(),
            b.secondaryIds(),
            b.nestedChanges(),
            b.filterUsed(),
            null
        )))
    );
  }

  public static class TransitionModel<I, O> /*extends TransitionModelBuilder<I, O, O> */{

    private final ModelContext<I, O> modelContext;
    private final Function<TransitionContext<I>, Mono<ChangeContext<O>>> chain;

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
      return String.format("onEvent(%s).to(%s)", modelContext.eventType.name(), modelContext.to);
    }

    TransitionModel(
        ModelContext<I, O> modelContext,
        Function<TransitionContext<I>, Mono<ChangeContext<O>>> chain
    ) {
      this.modelContext = modelContext;
      this.chain = chain;
    }

    public List<OutgoingRequestModel<?, ?>>  outgoingRequests() {
      return modelContext.outgoingRequests();
    }

//    public OutgoingResponseModel<?, ?> outgoingResponse() {
//      return modelContext.outgoingResponse();
//    }

    public TransitionModel<Rollback.Data, Void> reverseModel() {
      return modelContext.reverseModel();
    }

    public List<ScheduledEvent<?, ?>> scheduledEvents() {
      return modelContext.scheduledEvents();
    }

    public Mono<ChangeSet<O>> calculate(
        State fromState,
        IncomingMessage incomingMessage,
        StateMachine stateMachine,
        Clock clock,
        InputEvent<I> input,
        EventLog eventLog,
        ZonedDateTime timestamp,
        String correlationId
    ) {
      System.out.println(stateMachine.getClass().getName() + ": calculate: fromState=" + fromState + ", input=" + input.eventType());
      Function<TransitionContext<I>, Mono<ChangeContext<O>>> finalBuilderFunction;
      if (input.eventType() instanceof BasicEventType.Rollback) {
        finalBuilderFunction = chain.andThen(changeSet -> changeSet.flatMap(c -> stateMachine.calculateRollbackChanges(timestamp, eventLog, (InputEvent<BasicEventType.Rollback.Data>)input)
            .map(rollbackChangeSet -> new ChangeContext<>(
                c.transitionContext(),
                c.outgoingRequests(),
                //c.outgoingResponse(),
                c.secondaryIds(),
                join(c.nestedChanges(), rollbackChangeSet),
                c.filterUsed(),
                c.stepOutput()
            ))));
      } else {
        finalBuilderFunction = chain;
      }
      return finalBuilderFunction
          .apply(new TransitionContext<>(fromState, timestamp, correlationId, stateMachine, input, eventLog))
          .map(finalChangeContext -> {
            List<ChangeSet<?>> nestedChanges = finalChangeContext.nestedChanges();
            // Any nested rejected result yields final rejected result
            Optional<ChangeSet<?>> rejected = nestedChanges.stream().filter(c -> c.result().isRejected()).findFirst();
            if (rejected.isPresent()) {
              return ChangeSet.empty(ProcessResult.rejected(rejected.get().result().error()));
            }
            // Any other nested negative result yields that as final result
            var otherNegative = nestedChanges.stream().filter(c -> c.result().notAcceptedNorRepeated()).findFirst();
            if (otherNegative.isPresent()) {
              return ChangeSet.empty(otherNegative.get().result());
            }
            Event<O> resultingEvent = null;
            // Do not include change for this transition if there is an alternative
            List<Change> changes;
            if (finalChangeContext.filterUsed()) {
              System.out.println(modelContext.description() + ": Filter used, so skipping event " + modelContext.eventType() + " for this transition");
              changes = nestedChanges.stream().flatMap(cs -> cs.changes().stream()).toList();
            } else {
              resultingEvent = modelContext.isReversal() ? null : new Event<>(
                  eventLog.lastEventNumber() + 1,
                  modelContext.eventType(),
                  clock,
                  incomingMessage instanceof Message.IncomingRequest r ? r.messageId() : null,
                  incomingMessage instanceof Message.IncomingRequest r ? r.clientId() : null,
                  finalChangeContext.stepOutput
              );
              changes = join(
                  nestedChanges.stream().flatMap(cs -> cs.changes().stream()).toList(),
                  change(resultingEvent, eventLog, modelContext, incomingMessage, finalChangeContext)
              );
            }
            return new ChangeSet<>(ProcessResult.accepted(), changes, new EventReference<>(eventLog.entityId(), resultingEvent));
          }
      );
    }

    public EventType<I, O> eventType() {
      return modelContext.eventType();
    }

    public State toState() {
      return modelContext.to();
    }

//    public State fromState() {
//      return modelContext.from();
//    }

    @SafeVarargs
    public static Map<State, List<TransitionModel<?, ?>>> mergeModels(
        Map<State, List<TransitionModel<?, ?>>> ...lists
    ) {
      return Arrays.stream(lists).flatMap(l -> l.entrySet().stream())
          .collect(Collectors.toMap(
              Map.Entry::getKey,
              Map.Entry::getValue,
              (e1, e2) -> Stream.concat(e1.stream(), e2.stream()).toList()
          ));
    }


    public List<Filter<?,?,?>> filters() {
      return modelContext.filters();
    }
  }

  private static <O> Change change(
      Event<O> event,
      EventLog eventLog,
      ModelContext<?, O> modelContext,
      IncomingMessage incomingMessage,
      ChangeContext<O> changeContext
  ) {
    return new Change() {
      @Override
      public String toString() {
        return entityModel().name() + "/" + entityId().value() + "/" + newEvent();
      }

      @Override
      public EntityModel entityModel() {
        return eventLog.entityModel();
      }

      @Override
      public EntityId entityId() {
        return eventLog.entityId();
      }

      @Override
      public Event<O> newEvent() {
        return event;
      }

      @Override
      public State toState() {
        return modelContext.to();
      }

      @Override
      public List<SecondaryId> newSecondaryIds() {
        return changeContext.secondaryIds();
      }

      @Override
      public IncomingMessage.IncomingRequest incomingRequest() {
        return incomingMessage instanceof Message.IncomingRequest r ? r : null;
      }

//      @Override
//      public OutgoingResponse outgoingResponse() {
//        return changeContext.outgoingResponse();
//      }

      @Override
      public List<OutgoingRequest> outgoingRequests() {
        return changeContext.outgoingRequests();
      }

      @Override
      public IncomingMessage.IncomingResponse incomingResponse() {
        return incomingMessage instanceof Message.IncomingResponse r ? r : null;
      }

      @Override
      public ZonedDateTime deadline() {
        return (modelContext.to() != null ? modelContext.to() : changeContext.transitionContext().from())
            .timeout()
            .map(timeout -> changeContext.transitionContext.timestamp.plus(timeout.duration()))
            .orElse(null);
      }

      @Override
      public String correlationId() {
        return changeContext.transitionContext.correlationId;
      }
    };
  }


}
