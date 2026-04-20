package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.BasicEventType.Rollback;
import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.Choice;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.OutgoingRequestModel.Builder;
import com.github.thxmasj.statemachine.StateMachine.ChangeSet;
import com.github.thxmasj.statemachine.StateMachine.CircularChange;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.statemachine.StateMachine.RejectedEvent;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialReversalChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.NewIdentifierChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutgoingRequestChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.PendingTriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.RejectedTriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ResolvedPendingTriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ReversalChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ScheduledChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.TriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.UnknownEntityTriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithToState.DuplicateModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.Message.IncomingMessage;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
      List<OutgoingRequestModel<?, ?>> outgoingRequests,
      List<Filter<?, ?, ?>> filters,
      List<ScheduledEvent<?, ?>> scheduledEvents,
      TransitionModel<Data, Data> reverseModel,
      TransitionModel<Tuple4<I, EntityModel, EventType<?, ?>, String>, O> rejectModel,
      List<DuplicateModel<I, O>> duplicateModels
  ) {

    public String description() {
      if (reversal)
        return "<reversal>";
      return String.format("%s -> %s", eventType.name(), to != null ? to : "<self>");
    }

  }

  public record TransitionContext<I>(
      State from,
      int eventNumber,
      ZonedDateTime timestamp,
      String correlationId,
      StateMachine stateMachine,
      InputEvent<I> input,
      EventLog log,
      ChangeSet<?> otherChanges
  ) {

    public Mono<EventLog> log(SecondaryId<?> secondaryId, EntityModel entityModel) {
      return stateMachine.trace(secondaryId, entityModel);
    }

  }

  public sealed interface ChangeContext<T> {
    TransitionContext<?> transitionContext();

    ChangeContext<?> previous();

    T stepOutput();

    default boolean isChoiceTransition() {
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        if (changeContext instanceof ChoiceChangeContext) return true;
        changeContext = changeContext.previous();
      }
      return false;
    }

    default List<ChangeSet<?>> nestedChanges() {
      ArrayList<ChangeSet<?>> changes = new ArrayList<>();
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        switch (changeContext) {
          case TriggerChangeContext<?, ?> c -> changes.add(c.changeSet);
          case PendingTriggerChangeContext<?, ?> c -> {}
          case ResolvedPendingTriggerChangeContext<?, ?> c -> {
            System.out.println("Including resolved pending trigger changes in nested changes: " + c.changeSet);
            changes.add(c.changeSet);
          }
          case RejectedTriggerChangeContext<?, ?> c -> changes.add(c.changeSet);
          case UnknownEntityTriggerChangeContext<?, ?> c -> changes.add(c.changeSet);
          case ChoiceChangeContext<?, ?> c -> changes.add(c.changeSet);
          case InitialReversalChangeContext<?> c -> c.changeSet.ifPresent(changes::add);
          case ReversalChangeContext<?> c -> changes.add(c.changeSet);
          case ScheduledChangeContext<?, ?> c -> changes.add(c.changeSet);
          case InitialChangeContext<?> _,
               NewIdentifierChangeContext<?, ?> _,
               OutgoingRequestChangeContext<?> _,
               OutputChangeContext<?> _ -> {}
        }
        changeContext = changeContext.previous();
      }
      return changes;
    }

    default List<ChangeSet<?>> nestedChangesWithoutTriggeredFailures() {
      ArrayList<ChangeSet<?>> changes = new ArrayList<>();
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        switch (changeContext) {
          case TriggerChangeContext<?, ?> c -> changes.add(c.changeSet);
          case PendingTriggerChangeContext<?, ?> _ -> {}
          case ResolvedPendingTriggerChangeContext<?, ?> c -> changes.add(c.changeSet);
          case RejectedTriggerChangeContext<?, ?> _ -> {}
          case UnknownEntityTriggerChangeContext<?, ?> _ -> {}
          case ChoiceChangeContext<?, ?> c -> changes.add(c.changeSet);
          case InitialReversalChangeContext<?> c -> c.changeSet.ifPresent(changes::add);
          case ReversalChangeContext<?> c -> changes.add(c.changeSet);
          case ScheduledChangeContext<?,?> c -> changes.add(c.changeSet);
          case InitialChangeContext<?> _,
               NewIdentifierChangeContext<?, ?> _,
               OutgoingRequestChangeContext<?> _,
               OutputChangeContext<?> _ -> {}
        }
        changeContext = changeContext.previous();
      }
      return changes;
    }

    default List<SecondaryId<?>> secondaryIds() {
      ArrayList<SecondaryId<?>> secondaryIds = new ArrayList<>();
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        if (changeContext instanceof ChangeContext.NewIdentifierChangeContext<?,?> c)
          secondaryIds.add(c.id);
        changeContext = changeContext.previous();
      }
      return secondaryIds;
    }

    default List<OutgoingRequest> outgoingRequests() {
      ArrayList<OutgoingRequest> outgoingRequests = new ArrayList<>();
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        if (changeContext instanceof OutgoingRequestChangeContext<?> c)
          outgoingRequests.add(c.outgoingRequest);
        changeContext = changeContext.previous();
      }
      return outgoingRequests;
    }

    record InitialChangeContext<T>(
        TransitionContext<?> transitionContext,
        ChangeContext<Void> previous,
        T stepOutput
    ) implements ChangeContext<T> {}

    record InitialReversalChangeContext<T>(
        TransitionContext<?> transitionContext,
        ChangeContext<Void> previous,
        Optional<ChangeSet<?>> changeSet,
        T stepOutput
    ) implements ChangeContext<T> {}

    record ReversalChangeContext<T>(
        TransitionContext<?> transitionContext,
        ChangeContext<?> previous,
        ChangeSet<?> changeSet,
        T stepOutput
    ) implements ChangeContext<T> {

      public ReversalChangeContext {
        requireNonNull(changeSet);
      }
    }

    record TriggerChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        ChangeSet<O1> changeSet
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      public TriggerChangeContext {
        requireNonNull(changeSet);
        System.out.println("Creating TriggerChangeContext: " + changeSet.result());
      }

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        return tuple(previous.stepOutput(), changeSet.result());
      }
    }

    record ChoiceChangeContext<T, O1>(
        TransitionContext<?> transitionContext, ChangeContext<T> previous,
        ChangeSet<O1> changeSet
    ) implements ChangeContext<T> {

      public ChoiceChangeContext {
        requireNonNull(changeSet);
        System.out.println("Creating ChoiceChangeContext: " + changeSet.result());
      }

      @Override
      public T stepOutput() {
        return previous.stepOutput();
      }
    }

    record ScheduledChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        ChangeSet<O1> changeSet
    ) implements ChangeContext<T> {

      public ScheduledChangeContext {
        requireNonNull(changeSet);
      }

      @Override
      public T stepOutput() {
        return previous.stepOutput();
      }
    }

    record RejectedTriggerChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        ChangeSet<O1> changeSet,
        Rejected<O1> rejectedResult
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      public RejectedTriggerChangeContext {
        requireNonNull(changeSet);
        System.out.println("Creating RejectedTriggerChangeContext: " + changeSet.result());
      }

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        return tuple(previous.stepOutput(), rejectedResult);
        // Can't do this, as test() on choice predicates rely on it
        //throw rejectedResult().exception();
      }
    }

    record PendingTriggerChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        EventTrigger<T, ?, O1> eventTrigger,
        CircularChange e
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      public PendingTriggerChangeContext {
        System.out.println("Creating PendingTriggerChangeContext for " + eventTrigger.eventSpec().eventType().name() + " on " + eventTrigger.entityModel().name() + " (" + e.getMessage() + ")");
      }

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        throw new IllegalStateException("Event trigger is pending");
      }
    }

    record ResolvedPendingTriggerChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        ChangeSet<O1> changeSet
    ) implements ChangeContext<T> {

      public ResolvedPendingTriggerChangeContext {
        System.out.println("Creating ResolvedPendingTriggerChangeContext");
      }

      @Override
      public T stepOutput() {
        return previous().stepOutput();
      }
    }

    record UnknownEntityTriggerChangeContext<T, O1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        ChangeSet<O1> changeSet,
        UnknownId<O1> unknownIdResult
    ) implements ChangeContext<Tuple2<T, ProcessResult<O1>>> {

      public UnknownEntityTriggerChangeContext {
        requireNonNull(changeSet);
        System.out.println("Creating UnknownEntityTriggerChangeContext: " + changeSet.result());
      }

      @Override
      public Tuple2<T, ProcessResult<O1>> stepOutput() {
        return tuple(previous.stepOutput(), unknownIdResult);
        // Can't do this, as test() on choice predicates rely on it
        //throw unknownIdResult.exception();
      }
    }

    record NewIdentifierChangeContext<T, T1>(
        TransitionContext<?> transitionContext,
        ChangeContext<T> previous,
        SecondaryId<T1> id
    ) implements ChangeContext<Tuple2<T, SecondaryId<T1>>> {
      @Override
      public Tuple2<T, SecondaryId<T1>> stepOutput() {
        return tuple(previous.stepOutput(), id);
      }
    }

    record OutgoingRequestChangeContext<T>(TransitionContext<?> transitionContext, ChangeContext<T> previous,
                                               OutgoingRequest outgoingRequest) implements ChangeContext<T> {

      @Override
      public T stepOutput() {
        return previous.stepOutput();
      }

    }

    record OutputChangeContext<T>(
        TransitionContext<?> transitionContext,
        ChangeContext<?> previous,
        T stepOutput
    ) implements ChangeContext<T> {}

  }

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

    public WithToState<I, O> to(State toState) {
      return new WithToState<>(new ModelContext<>(false, toState, eventType, List.of(), List.of(), List.of(), List.of(), null, null, List.of()));
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

    private <T> Mono<ChangeContext<T>> initialChangeContext(TransitionContext<I> transitionContext, T stepOutput) {
      return Mono.just(new InitialChangeContext<>(
          transitionContext,
          null,
          stepOutput
      ));
    }

    public WithToState<I, O> whenReject(TransitionModel<Tuple4<I, EntityModel, EventType<?, ?>, String>, O> rejectModel) {
      return new WithToState<>(new ModelContext<>(
          modelContext.reversal,
          modelContext.to,
          modelContext.eventType,
          modelContext.triggers,
          modelContext.outgoingRequests,
          modelContext.filters,
          modelContext.scheduledEvents,
          modelContext.reverseModel,
          rejectModel,
          modelContext.duplicateModels
      ));
    }

    record DuplicateModel<I, O>(
        SecondaryIdModel<?> idModel,
        BiFunction<I, EventLog, Boolean> filter,
        TransitionModel<Tuple2<I, EventLog>, O> transitionModel
    ) {}

    public WithToState<I, O> whenDuplicate(
        SecondaryIdModel<?> idModel,
        BiFunction<I, EventLog, Boolean> filter,
        TransitionModel<Tuple2<I, EventLog>, O> transitionModel
    ) {
      return new WithToState<>(new ModelContext<>(
          modelContext.reversal,
          modelContext.to,
          modelContext.eventType,
          modelContext.triggers,
          modelContext.outgoingRequests,
          modelContext.filters,
          modelContext.scheduledEvents,
          modelContext.reverseModel,
          modelContext.rejectModel,
          join(modelContext.duplicateModels, new DuplicateModel<>(idModel, filter, transitionModel))
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

    public TransitionModel<I, O> output() {
      return new TransitionModel<>(modelContext, transitionContext -> initialChangeContext(transitionContext, null));
    }

  }

  private final ModelContext<I, O> modelContext;
  private final Function<TransitionContext<I>, Mono<ChangeContext<T>>> builderFunction;

  private TransitionModelBuilder(
      ModelContext<I, O> modelContext,
      Function<TransitionContext<I>, Mono<ChangeContext<T>>> builderFunction
  ) {
    this.modelContext = modelContext;
    this.builderFunction = builderFunction;
  }

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
        var requestModelBuilder = Builder.request(
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

      public TransitionModelBuilder<I, T, O> schedule(EventType<Void, ?> eventType, Duration deadline) {
        return complete().schedule(eventType, deadline);
      }

      public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Data, Data> reverseModel) {
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

      public <I2, O2> WithEventType<I, Tuple2<T, ProcessResult<O1>>, O, I2, O2> trigger(EventType<I2, O2> eventType) {
        return new WithEventType<>(complete(), eventType);
      }

      public <T1> WithOutgoingRequest<I, Tuple2<T, ProcessResult<O1>>, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
        return new WithOutgoingRequest<>(complete(), outgoingRequestCreator);
      }

      public <I2, O2> TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> schedule(EventType<I2, O2> eventType, Duration deadline) {
        return complete().schedule(eventType, deadline);
      }

      public WithFilter<I, Tuple2<T, ProcessResult<O1>>, O> when(Predicate<Tuple2<T, ProcessResult<O1>>> filter) {
        return new WithFilter<>(complete(), filter);
      }

      public <T1> TransitionModelBuilder<I, Tuple2<Tuple2<T, ProcessResult<O1>>, SecondaryId<T1>>, O> newIdentifier(
          SecondaryIdModel<T1> model,
          Function<Tuple2<T, ProcessResult<O1>>, T1> data
      ) {
        return complete().newIdentifier(model, data);
      }

      public TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> reversible(TransitionModel<Data, Data> reverseModel) {
        return complete().reversible(reverseModel);
      }

      public TransitionModel<I, O> output(Function<Tuple2<T, ProcessResult<O1>>, O> f) {
        return complete().output(f);
      }

      public TransitionModel<I, O> output() {
        return complete().output();
      }

      private EventTrigger<T, I1, O1> completeTrigger() {
        System.out.println(
            this.entity.eventTypeAndData.eventType.builder.modelContext.description() + ": Model completeTrigger "
                + this.entity.eventTypeAndData.eventType.eventType.name() + " on "
                + ofNullable(this.entity.entityModel).map(EntityModel::name).orElse("<entity model missing>")
        );
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
    System.out.println(modelContext.description() + ": Model trigger " + eventType.name());
    return new WithEventType<>(this, eventType);
  }

  public <T1> WithOutgoingRequest<I, T, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
    return new WithOutgoingRequest<>(this, outgoingRequestCreator);
  }

  public TransitionModelBuilder<I, T, O> when2(Map<Predicate<T>, Alternative<T, ?, ?>> choices) {
    var builder = this;
    for (var entry : choices.entrySet()) {
      builder = builder.when(entry.getKey()).then(entry.getValue());
    }
    return builder;
  }

  public TransitionModelBuilder<I, T, O> when(Map<Predicate<T>, TransitionModel<T, ?>> choices) {
    var builder = this;
    for (var entry : choices.entrySet()) {
      builder = builder.when(entry.getKey()).then(entry.getValue());
    }
    return builder;
  }

  public TransitionModelBuilder<I, T, O> when(List<Choice<T, ?, ?>> choices) {
    var builder = this;
    for (var choice : choices) {
      builder = builder.when(choice);
    }
    return builder;
  }

  public <I1, O1> TransitionModelBuilder<I, T, O> when(Choice<T, I1, O1> choice) {
    return when(choice.condition().and(choice.adapter().andThen(Validation::isValid)::apply)).then(
        choice.then(),
        choice.adapter().andThen(Validation::valid)
    );
  }

  public WithFilter<I, T, O> when(Predicate<T> filter) {
    return new WithFilter<>(this, filter);
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
      System.out.println("Completing filter for " + filter.alternative().model().eventType().name());
      var modelContext = new ModelContext<>(
          this.builder.modelContext.reversal,
          this.builder.modelContext.to,
          this.builder.modelContext.eventType,
          this.builder.modelContext.triggers,
          this.builder.modelContext.outgoingRequests,
          join(this.builder.modelContext.filters, filter),
          this.builder.modelContext.scheduledEvents,
          this.builder.modelContext.reverseModel,
          this.builder.modelContext.rejectModel,
          this.builder.modelContext.duplicateModels
      );
      return new TransitionModelBuilder<>(
          modelContext,
          this.builder.builderFunction.andThen(changeContext -> changeContext
              .flatMap(c -> {
                if (c.isChoiceTransition()) {
                  log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + " skipped, another alternative already chosen");
                  return Mono.just(c);
                }
                log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + ": testing");
                if (!filter.predicate().test(c.stepOutput())) {
                  log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + " not matching");
                  return Mono.just(c);
                }
                log(modelContext, "using alternative: " + filter.alternative().model().eventType().name());
                return filter.alternative().model().calculate(
                        new TransitionContext<>(
                            c.transitionContext().from(),
                            c.transitionContext().eventNumber,
                            c.transitionContext().timestamp(),
                            c.transitionContext().correlationId(),
                            c.transitionContext().stateMachine(),
                            new InputEvent<>(
                                filter.alternative().model().eventType(),
                                filter.alternative().dataAdapter().apply(c.stepOutput())
                            ),
                            c.transitionContext().log(),
                            null
                        ),
                        Clock.systemUTC(), // TODO
                        null
                    )
                    .map(change -> new ChoiceChangeContext<>(c.transitionContext(), c, change));
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
        new ModelContext<>(true /* !! */, null, null, List.of(), List.of(), List.of(), List.of(), null, null, List.of()),
        transitionContext -> assembler.apply(transitionContext.log(), (EventType<Data, Data>)transitionContext.input.eventType())
            .map(assembled -> new InitialReversalChangeContext<>(
                transitionContext,
                null,
                ofNullable(transitionContext.otherChanges),
                assembled
            ))
    );
  }

  public <T1> TransitionModelBuilder<I, Tuple2<T, SecondaryId<T1>>, O> newIdentifierInGroup(SecondaryIdModel<T1> model, Function<T, Object> data) {
    return new TransitionModelBuilder<>(
        modelContext,
        builderFunction.andThen(changeContext -> changeContext.flatMap(c ->
            c.transitionContext().stateMachine.next(model, data.apply(c.stepOutput()))
                .map(id -> new NewIdentifierChangeContext<>(c.transitionContext(), c, id))
        ))
    );
  }

  public <T1> TransitionModelBuilder<I, Tuple2<T, SecondaryId<T1>>, O> newIdentifier(SecondaryIdModel<T1> model, Function<T, T1> data) {
    return new TransitionModelBuilder<>(
        modelContext,
        builderFunction.andThen(changeContext -> changeContext.map(c ->
            new NewIdentifierChangeContext<>(c.transitionContext(), c, new SecondaryId<>(model, data.apply(c.stepOutput())))
        ))
    );
  }

  private <I1, O1> TransitionModelBuilder<I, T, O> schedule(EventType<I1, O1> eventType, Duration deadline) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            modelContext.triggers,
            modelContext.outgoingRequests,
            modelContext.filters,
            join(modelContext.scheduledEvents, new ScheduledEvent<>(eventType, deadline)),
            modelContext.reverseModel,
            modelContext.rejectModel,
            modelContext.duplicateModels
        ),
        builderFunction.andThen(changeContext -> changeContext.flatMap(c -> c.transitionContext()
            .timestamp().isAfter(c.transitionContext().log().effectiveEvents().getLast().timestamp().plus(deadline)) ?
            c.transitionContext().stateMachine().calculateChange(
                eventType,
                c.transitionContext().log(),
                c.transitionContext().timestamp(),
                c.transitionContext().correlationId()
            ).map(changeSet -> new ScheduledChangeContext<>(c.transitionContext(), c, changeSet)) : Mono.just(c)))
    );
  }

  /*
      Function<Mono<ChangeContext<T>>, Mono<ChangeContext<Tuple2<T, ProcessResult<O1>>>>> f =
        changeContext -> changeContext
            .flatMap(c -> c.transitionContext()
                      .stateMachine()
                      .calculateTriggeredEvent(eventTrigger, c.stepOutput(), c.transitionContext().correlationId, c.transitionContext().timestamp, c.nestedChanges())
                      .map(changeSet -> new TriggerChangeContext<>(c.transitionContext(), c, changeSet))
                      .contextWrite(ctx -> {
                        EventLog eventLog = c.transitionContext().log();
                        if (modelContext.eventType() == null)
                          return ctx;
                        System.out.println("Creating fake event for " + modelContext.eventType.name());
                        var sessionLog = eventLog.withNewEvent(new Event<>(
                            eventLog.entityId().value(),
                            eventLog.lastEventNumber() + 1,
                            modelContext.eventType,
                            Clock.systemUTC()
                        ));
                        return ctx.put(sessionLog.entityModel(), sessionLog.entityId())
                            .put(sessionLog.entityId(), sessionLog);
                      })

              );
    var eventTriggers = join(modelContext.triggers, eventTrigger);
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            eventTriggers,
            modelContext.outgoingRequests,
            modelContext.filters,
            modelContext.scheduledEvents,
            modelContext.reverseModel,
            modelContext.rejectModel,
            modelContext.duplicateModels
        ),
        builderFunction.andThen(f)
    );

   */

  public TransitionModelBuilder<I, T, O> reversible(TransitionModel<Data, Data> reverseModel) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            modelContext.triggers,
            modelContext.outgoingRequests,
            modelContext.filters,
            modelContext.scheduledEvents,
            reverseModel,
            modelContext.rejectModel,
            modelContext.duplicateModels
        ),
        builderFunction
    );
  }

  private <I1, O1> TransitionModelBuilder<I, Tuple2<T, ProcessResult<O1>>, O> trigger(EventTrigger<T, I1, O1> eventTrigger) {
    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<Tuple2<T, ProcessResult<O1>>>>> f =
        changeContext -> changeContext
            .flatMap(c -> Mono.just("").doOnNext(_ -> log(modelContext, "BEFORE calculateTriggeredEvent " + eventTrigger.eventSpec().eventType().name()))
                    .then(c.transitionContext().stateMachine().calculateTriggeredEvent(eventTrigger, c, c.nestedChanges(), false))
                    .onErrorResume(CircularChange.class, e -> Mono.just(new ChangeSet<>(ProcessResult.pending(eventTrigger.eventSpec().eventType(), e), List.of())))
                    .doOnNext(changeSet -> log(modelContext, "calculateTriggeredEvent " + eventTrigger.eventSpec().eventType().name() + " result: " + changeSet.result().getClass().getSimpleName()))
                    .map(changeSet -> switch (changeSet.result()) {
                      case Accepted<?> _ -> new TriggerChangeContext<>(c.transitionContext(), c, changeSet);
                      case ProcessResult.Rejected<O1> r ->
                          new RejectedTriggerChangeContext<>(c.transitionContext(), c, changeSet, r);
                      case ProcessResult.UnknownId<O1> r ->
                          new UnknownEntityTriggerChangeContext<>(c.transitionContext(), c, changeSet, r);
                      case ProcessResult.DuplicateId<O1> _,
                           ProcessResult.Failed<O1> _,
                           ProcessResult.Raced<O1> _ -> throw new IllegalStateException();
                      case ProcessResult.Pending<O1> r -> {
                        log(modelContext, "calculateTriggeredEvent got pending result: " + r);
                        yield new PendingTriggerChangeContext<>(c.transitionContext(), c, eventTrigger, r.exception());
                      }
                    })
                      .contextWrite(ctx -> {
                        EventLog eventLog = c.transitionContext().log();
                        if (modelContext.eventType() == null)
                          return ctx;
//                        System.out.println("Creating fake event for " + modelContext.eventType.name());
//                        var sessionLog = eventLog.withNewEvent(new Event<>(
//                            eventLog.entityId().value(),
//                            eventLog.lastEventNumber() + 1,
//                            modelContext.eventType,
//                            Clock.systemUTC()
//                        ));
                        return ctx
                            .put(eventLog.entityModel(), eventLog.entityId());
                            //.put(sessionLog.entityId(), sessionLog);
                      })

              );
    var eventTriggers = join(modelContext.triggers, eventTrigger);
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            eventTriggers,
            modelContext.outgoingRequests,
            modelContext.filters,
            modelContext.scheduledEvents,
            modelContext.reverseModel,
            modelContext.rejectModel,
            modelContext.duplicateModels
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

  private static <K, V> Map<K, V> join(Map<K, V> map, K key, V value) {
    var m = new HashMap<K, V>(map.size() + 1);
    m.putAll(map);
    m.put(key, value);
    return unmodifiableMap(m);
  }

  private <T1> TransitionModelBuilder<I, T, O> trigger(OutgoingRequestModel<T, T1> requestModel) {
    Function<Mono<ChangeContext<T>>, Mono<ChangeContext<T>>> f =
        c -> c.flatMap(changeContext ->
            changeContext.transitionContext().stateMachine().createOutgoingRequest(
                modelContext.reversal,
                changeContext.transitionContext().timestamp(),
                new Entity(
                    changeContext.transitionContext().log().entityId(),
                    changeContext.transitionContext().log().secondaryIds(),
                    changeContext.transitionContext().log().entityModel()
                ),
                changeContext.stepOutput(),
                changeContext.transitionContext().eventNumber,
                // TODO: This assumes nested change with parent entity is always calculated before outgoing request. Model does not restrict to that.
                //       Refactor as the concept is weak anyways.
                changeContext.nestedChanges().stream().flatMap(nestedChange -> nestedChange.changes().stream()).toList(),
                requestModel,
                changeContext.transitionContext().correlationId()
                )
                .map(request -> new OutgoingRequestChangeContext<>(changeContext.transitionContext(), changeContext, request))
        );
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            modelContext.triggers,
            join(modelContext.outgoingRequests, requestModel),
            modelContext.filters,
            modelContext.scheduledEvents,
            modelContext.reverseModel,
            modelContext.rejectModel,
            modelContext.duplicateModels
        ),
        builderFunction.andThen(f)
    );
  }

  public TransitionModel<I, O> output(Function<T, O> f) {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new OutputChangeContext<>(b.transitionContext(), b, f.apply(b.stepOutput()))))
    );
  }

  public TransitionModel<I, O> output() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new OutputChangeContext<>(b.transitionContext(), b, null)))
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
      return String.format("onEvent(%s).to(%s)", modelContext.eventType.name(), modelContext.to != null ? modelContext.to : "<self>");
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

    public TransitionModel<Data, Data> reverseModel() {
      return modelContext.reverseModel();
    }

    public TransitionModel<Tuple4<I, EntityModel, EventType<?, ?>, String>, O> rejectModel() {
      return modelContext.rejectModel();
    }

    public List<TransitionModel<Tuple2<I, EventLog>, O>> duplicateModels() {
      return modelContext.duplicateModels().stream().map(DuplicateModel::transitionModel).toList();
    }

    public Optional<TransitionModel<Tuple2<I, EventLog>, O>> duplicateModel(
        SecondaryIdModel<?> idModel,
        I input,
        EventLog originalLog
    ) {
      return modelContext.duplicateModels().stream()
          .filter(m -> m.idModel() == idModel)
          .filter(m -> m.filter().apply(input, originalLog))
          .map(DuplicateModel::transitionModel)
          .findFirst();
    }

    public List<ScheduledEvent<?, ?>> scheduledEvents() {
      return modelContext.scheduledEvents();
    }

//    private ChangeSet<Void> merge(List<ChangeSet<?>> sets) {
//      return sets.stream()
//          .map(ChangeSet::result)
//          .filter(result -> !result.isAccepted())
//          .findFirst()
//          .map(processResult -> new ChangeSet<Void>(processResult, null, null))
//          .orElseGet(() -> new ChangeSet<>(
//              new ProcessResult.Accepted(),
//              sets.stream().flatMap(s -> s.changes().stream()).toList(),
//              null
//          ));
//    }

    public Mono<ChangeSet<O>> calculateReverse(
        int eventNumber,
        StateMachine stateMachine,
        Clock clock,
        InputEvent<I> input,
        EventLog eventLog,
        ZonedDateTime timestamp,
        String correlationId,
        ChangeSet<I> otherChanges
    ) {
      log(modelContext, "calculate reverse");
      TransitionContext<I> transitionContext = new TransitionContext<>(
          null,
          eventNumber,
          timestamp,
          correlationId,
          stateMachine,
          input,
          eventLog,
          otherChanges
      );
      return calculateInternal(
          transitionContext,
          chain,
          null,
          clock
      );
    }

    public Mono<ChangeSet<O>> calculate(TransitionContext<I> transitionContext, Clock clock, IncomingMessage incomingMessage) {
      log(modelContext, "calculate " + transitionContext.input().eventType().name() + " on " + transitionContext.log().entityModel().name());
      Function<TransitionContext<I>, Mono<ChangeContext<O>>> finalBuilderFunction;
      if (transitionContext.input.eventType() instanceof Rollback eventType && transitionContext.input.data() instanceof Data data) {
        finalBuilderFunction = chain.andThen(changeContext -> changeContext
            .flatMap(c -> rollbackChangeSet(eventType, data, transitionContext.log(), transitionContext.stateMachine(), clock, transitionContext.timestamp(), transitionContext.correlationId())
                .map(rollbackChangeSet -> (ChangeContext<O>)new ReversalChangeContext<>(
                    c.transitionContext(),
                    c,
                    rollbackChangeSet,
                    c.stepOutput()
                )).switchIfEmpty(Mono.just(c))
            ));
      } else {
        finalBuilderFunction = chain;
      }
      return calculateInternal(
          transitionContext,
          finalBuilderFunction,
          incomingMessage,
          clock
      );
    }

    private Mono<ChangeSet<Data>> rollbackChangeSet(
        Rollback eventType,
        Data data,
        EventLog eventLog,
        StateMachine stateMachine,
        Clock clock,
        ZonedDateTime timestamp,
        String correlationId
    ) {
      Traverser traverser = stateMachine.traverser(eventLog.entityModel());
      State currentState = traverser.currentState(eventLog);
      int rollbackTo = data.toNumber();
      if (rollbackTo < 0) {
        rollbackTo = eventLog.lastEventNumber() + rollbackTo; // add negative
      }
      if (rollbackTo >= eventLog.lastEventNumber() || rollbackTo < 0) {
        return Mono.just(ChangeSet.empty(
            ProcessResult.rejected(eventType, new RejectedEvent(eventType, eventLog.entityModel(), eventLog.entityId(), currentState, rollbackTo))
        ));
//        , String.format(
//            "Can't rollback to event number %d (input=%d, last=%d)",
//            rollbackTo,
//            data.toNumber(),
//            eventLog.lastEventNumber()
//        )))));
      }
      var effectiveEvents = eventLog.effectiveEvents();
      List<Event<?>> eventsToRollback = effectiveEvents.subList(rollbackTo, effectiveEvents.size());
      Mono<ChangeSet<Data>> reverseChangeSet = Mono.empty();
      for (var event : eventsToRollback.reversed()) {
        var tm = traverser.transitionForEventNumber(eventLog, event.eventNumber());
        if (tm == null) {
          throw new IllegalStateException(String.format(
              "No transition found for event number %d",
              event.eventNumber()
          ));
        }
        if (tm.reverseModel() != null) {
          reverseChangeSet = reverseChangeSet.flatMap(cs -> tm.reverseModel().calculateReverse(
              event.eventNumber(),
              stateMachine,
              clock,
              new InputEvent<>(eventType, data),
              eventLog,
              timestamp,
              correlationId,
              cs
          )).switchIfEmpty(tm.reverseModel().calculateReverse(
              event.eventNumber(),
              stateMachine,
              clock,
              new InputEvent<>(eventType, data),
              eventLog,
              timestamp,
              correlationId,
              null
          ));
        }
      }
      return reverseChangeSet;
    }

    List<PendingTriggerChangeContext<?, ?>> pendingChanges(ChangeContext<?> x) {
      ArrayList<PendingTriggerChangeContext<?, ?>> pendingChanges = new ArrayList<>();
      ChangeContext<?> changeContext = x;
      while (changeContext != null) {
        if (changeContext instanceof PendingTriggerChangeContext<?, ?> c) {
          pendingChanges.add(c);
        }
        changeContext = changeContext.previous();
      }
      return pendingChanges;
    }

    private <T, O1> Mono<ChangeSet<O1>> calculatePendingChange(
        PendingTriggerChangeContext<T, O1> pendingChange,
        List<ChangeSet<?>> nestedChanges
    ) {
      // TODO: nestedChanges, which is used for eventLogFromNestedChanges,
      //  is not up2date after resolving circle as they are calculated from pendingChange.previous(),
      System.out.println("Calculating pending change for " + pendingChange.eventTrigger.eventSpec().eventType().name());
      return pendingChange.transitionContext().stateMachine().calculateTriggeredEvent(
          pendingChange.eventTrigger(),
          pendingChange.previous(),
          nestedChanges,
          true
      );
    }

    private Mono<ChangeSet<O>> calculateInternal(
        TransitionContext<I> transitionContext,
        Function<TransitionContext<I>, Mono<ChangeContext<O>>> builderFunction,
        IncomingMessage incomingMessage,
        Clock clock
    ) {
      log(modelContext, "calculateInternal " + transitionContext.input().eventType().name());
//      builderFunction = builderFunction.andThen(changeContext -> changeContext.flatMap(c -> {
//            List<PendingTriggerChangeContext<?, ?>> pendingChanges = pendingChanges(c);
//        System.out.println("Pending changes: " + pendingChanges.stream().map(pc -> pc.eventTrigger().eventSpec().eventType().name()).collect(joining(",")));
//            Mono<ChangeContext<O>> resultChangeContext = changeContext;
//            for (var pendingChange : pendingChanges) {
//              Mono<ChangeContext<O>> previousChangeContext = resultChangeContext;
//              resultChangeContext = calculatePendingChange(pendingChange, c.nestedChanges()).flatMap(changeSet -> previousChangeContext.map(prev -> new ResolvedPendingTriggerChangeContext<>(c.transitionContext(), prev, changeSet)));
//            }
//            return resultChangeContext;
//          }
//      ));
      return builderFunction.apply(transitionContext).map(finalChangeContext -> {
            log(modelContext, "calculate final " + transitionContext.input().eventType().name() + ": choice? " + finalChangeContext.isChoiceTransition());
            Event<O> resultingEvent = modelContext.reversal() ? null : new Event<>(
                transitionContext.log().entityId().value(),
                transitionContext.log().lastEventNumber() + 1,
                modelContext.eventType(),
                clock,
                finalChangeContext.stepOutput()
            );
            List<ChangeSet<?>> nestedChanges = finalChangeContext.isChoiceTransition() ?
                // Assume triggered events that were not accepted are handled by choice, so exclude them from error propagation
                finalChangeContext.nestedChangesWithoutTriggeredFailures() :
                finalChangeContext.nestedChanges();
            // Any nested rejected result yields final rejected result
            var nestedRejected = nestedChanges.stream()
                .filter(n -> n.result().isRejected())
                .map(ChangeSet::result)
                .findFirst();
            if (nestedRejected.isPresent()) {
              log(
                  modelContext,
                  "Nested rejected " + nestedRejected.get().rejected().exception().eventType().name() + ", so rejecting this one too (as choice=" + finalChangeContext.isChoiceTransition() + ")"
              );
              return ChangeSet.empty(ProcessResult.rejected(modelContext.eventType(), nestedRejected.get().rejected().exception()));
            }
            // Any nested unknown id result yields final unknown id result
            var nestedUnknownId = nestedChanges.stream()
                .filter(n -> n.result().isUnknownId())
                .map(ChangeSet::result)
                .findFirst();
            if (nestedUnknownId.isPresent()) {
              log(
                  modelContext,
                  "Propagated nested unknown id for " + nestedUnknownId.get().unknownId().eventType().name()
              );
              return ChangeSet.empty(ProcessResult.unknownId(modelContext.eventType(), nestedUnknownId.get().unknownId().exception()));
            }
//        if (nestedRejected.isPresent() && rejectModel() != null)
//          rejectModel().calculate(new TransitionContext<>(
//              transitionContext.from,
//              transitionContext.eventNumber,
//              transitionContext.timestamp,
//              transitionContext.correlationId,
//              transitionContext.stateMachine,
//              new InputEvent<>(
//                  rejectModel().eventType(),
//                  tuple(
//                      transitionContext.input.data(),
//                      transitionContext.log.entityModel(),
//                      transitionContext.input.eventType(),
//                      nestedRejected.get().rejected().reason()
//                  )
//              ),
//              transitionContext.log,
//              transitionContext.otherChanges
//          ), clock, incomingMessage);
//          return ChangeSet.empty(ProcessResult.rejected(modelContext.eventType(), eventLog.entityModel(), "Nested change rejected: " + nestedRejected.get().rejected().reason()));
//        // Any nested unknown id result yields final unknown id result
//        var nestedUnknownId = finalChangeContext.nestedChanges.stream()
//            .filter(n -> n.result().isUnknownId())
//            .map(ChangeSet::result)
//            .findFirst();
//        if (nestedUnknownId.isPresent())
//          return ChangeSet.empty(ProcessResult.unknownId(modelContext.eventType(), eventLog.entityModel(), nestedUnknownId.get().unknownId().id()));
//        // Any other nested negative result yields that as final result
//        var nestedNegative = finalChangeContext.nestedChanges.stream()
//            .filter(n -> !n.result().isAccepted())
//            .map(ChangeSet::result)
//            .findFirst();
//        if (nestedNegative.isPresent())
//          // TODO: Error message includes Record toString()
//          return ChangeSet.empty(ProcessResult.rejected(modelContext.eventType(), eventLog.entityModel(), "Nested change failed: " + nestedNegative.get()));

        List<Change> changes = join(
            nestedChanges.stream().flatMap(cs -> cs.changes().stream()).toList(),
            change(resultingEvent, transitionContext.log(), modelContext, incomingMessage, finalChangeContext)
        );
//        System.out.println("calculateInternal for " + modelContext.description() + "\nSteps:\n" + finalChangeContext.toString() + "\nChanges:\n" + changes.stream().map(c -> c.toString()).collect(
//            joining("\n")));
        return new ChangeSet<>(
            ProcessResult.accepted(transitionContext.log().entityId(), resultingEvent),
            changes
        );
      })
          .onErrorResume(
              // This occurs when the transition chain/function tries to access a ProcessResult on a rejected result
              RejectedEvent.class,
              e -> Mono.just(ChangeSet.empty(ProcessResult.rejected(modelContext.eventType(), e)))
                  .doOnNext(cs -> System.out.println("RejectedEvent in calculateInternal for " + modelContext.eventType().name()))
          )
          .onErrorResume(
              // This occurs when the transition chain/function tries to access a ProcessResult on a unknown id result
              UnknownEntity.class,
              e -> Mono.just(ChangeSet.empty(ProcessResult.unknownId(modelContext.eventType(), e)))
          );
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
        return eventLog().entityModel().name() + "/"
            + eventLog().entityId().value() + "/"
            + (newEvent() != null ? newEvent().typeName() + "/" + newEvent().eventNumber() + "/" + newEvent().timestamp() : "")
            + "/ids=" + newSecondaryIds().stream().map(id -> id.model().name() + "[" + id.data() + "]").collect(joining(","))
            + "/outrq=" + outgoingRequests().stream().map(rq -> rq.queue().name()).collect(joining(","));
      }

      @Override
      public EventLog eventLog() {
        return eventLog;
      }

      @Override
      public boolean storeEvent() {
        return !changeContext.isChoiceTransition();
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
      public List<SecondaryId<?>> newSecondaryIds() {
        return changeContext.secondaryIds();
      }

      @Override
      public List<OutgoingRequest> outgoingRequests() {
        return changeContext.outgoingRequests();
      }

      @Override
      public IncomingResponse incomingResponse() {
        return incomingMessage instanceof IncomingResponse r ? r : null;
      }

      @Override
      public ZonedDateTime deadline() {
        if (event == null) return null;
        if (modelContext.to == null) return null; // toSelf, don't use deadline
        return modelContext.to
            .timeout()
            .map(timeout -> changeContext.transitionContext().timestamp.plus(timeout.duration()))
            .orElse(null);
      }

      @Override
      public String correlationId() {
        return changeContext.transitionContext().correlationId;
      }
    };
  }

  static void log(ModelContext<?, ?> modelContext, String text) {
    System.out.println(modelContext.description() + ": " + text);
  }

}
