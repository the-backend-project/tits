package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.BuiltinEntities.EventReference;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.OutgoingRequestModel.Builder;
import com.github.thxmasj.statemachine.StateMachine.CircularChange;
import com.github.thxmasj.statemachine.StateMachine.IdentityResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.StateMachine.RejectedEvent;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.AssembledChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.CombinedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.IdentityChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutgoingRequestChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.PendingChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ScheduledChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.TriggerChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.UnknownIdChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithFilter.Alternative;
import com.github.thxmasj.statemachine.TransitionModelBuilder.WithToState.DuplicateModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple4;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import jakarta.validation.constraints.NotNull;
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
      int depth = this instanceof ChangeContext.OutputChangeContext<?> ? 0 : 1;
      for (ChangeContext<?> c = this; c != null; c = c.previous()) {
        if (c instanceof ChangeContext.OutputChangeContext<?> _) depth++;
        if (c instanceof ChangeContext.InitialChangeContext<?> i && --depth == 0) return i;
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

    default boolean isChoiceTransition() {
      ChangeContext<?> changeContext = this;
      while (changeContext != null) {
        if (changeContext instanceof ChoiceChangeContext) return true;
        changeContext = changeContext.previous();
      }
      return false;
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
        IncomingResponse incomingResponse,
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

    record OutgoingRequestChangeContext<T>(
        EntityModel entityModel,
        EntityId entityId,
        ChangeContext<T> previous,
        OutgoingRequest outgoingRequest
    ) implements ChangeContext<T> {

      @Override
      public T stepOutput() {
        return previous.stepOutput();
      }
      @Override public String toString() {
        return "OutgoingRequest: on " + outgoingRequest.queue().name() + " (" + outgoingRequest.message().requestLine() + ")";
      }

    }

    record OutputChangeContext<T>(
        ChangeContext<?> previous,
        ProcessResult<T> stepOutput
    ) implements ChangeContext<ProcessResult<T>> {
      @Override public String toString() {
        return "Output:" + (stepOutput.isAccepted() ? ((stepOutput.accepted().event().type() != null ? stepOutput.accepted().event().type().name() : "N/A") +  ":" + stepOutput.accepted().event().eventNumber()) : stepOutput.getClass().getSimpleName());
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

  private static <T> ArrayList<T> add(ArrayList<T> list, T element) {
    list.add(element);
    return list;
  }

  public static TransitionModel<Void, State> statusOn() {
    return new TransitionModel<>(
        new ModelContext<>(false, null, BuiltinEventTypes.Status, List.of(), List.of(), List.of(), List.of(), null, null, List.of()),
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

  public static TransitionModel<Data, Data> rollbackOn(@NotNull EventType<Data, Data> eventType) {
    return new TransitionModel<>(
        new ModelContext<>(false, null, eventType, List.of(), List.of(), List.of(), List.of(), null, null, List.of()),
        initialChangeContext -> initialChangeContext.flatMap(i -> {
          System.out.println("Validating rollback (" + eventType.name() + "), data [" + i.stepOutput() + "] initial change context: " + i);
          //int rollbackFrom = i.stepOutput().fromNumber();
          int rollbackTo = i.stepOutput().toNumber();
          int actualRollbackTo = rollbackTo;
          if (rollbackTo < 0) {
            actualRollbackTo = i.log().lastEventNumber() + rollbackTo; // add negative
          }
          System.out.println("Rolling back events:\n" + i.log().events().subList(actualRollbackTo, i.log().events().size()).stream().map(e -> "#" + e.eventNumber() + "[" + e.type().name() + "]").collect(Collectors.joining("\n")));
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

            System.out.println(
                "Rejecting rollback (rollbackTo=" + rollbackTo + /*", rollbackFrom=" + rollbackFrom +*/ "): " +
                actualRollbackTo + " >= " + i.log().lastEventNumber() +
                " || " + actualRollbackTo + " < 0 " +
                " || " + i.log().events().subList(actualRollbackTo, i.log().events().size()).stream().filter(e -> e.type() instanceof RequestEventType<?,?>).count() + " > 1"
                /*+ rollbackFrom + " != " +  i.log().lastEventNumber()*/
            );
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
                      null,
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

  /*
          int rollbackTo = data.toNumber();
        if (rollbackTo < 0) {
          rollbackTo = initialChangeContext.log().lastEventNumber() + rollbackTo; // add negative
        }
        int actualRollbackTo = rollbackTo;
        if (actualRollbackTo >= initialChangeContext.log().lastEventNumber() || actualRollbackTo < 0) {
          return Mono.just(new OutputChangeContext<>(
              initialChangeContext,
              ProcessResult.rejected(
                  eventType(),
                  new RejectedEvent(
                      eventType,
                      initialChangeContext.log().entityModel(),
                      initialChangeContext.log().entityId(),
                      initialChangeContext.from(),
                      data.toNumber()
                  )
              )
          ));
        }
        EventLog log = initialChangeContext.log();
        var effectiveEvents = log.effectiveEvents();
        List<Event<?>> eventsToRollback = effectiveEvents.subList(rollbackTo, effectiveEvents.size());
        Traverser traverser = initialChangeContext.stateMachine.traverser(log.entityModel());
        for (var event : eventsToRollback.reversed()) {
          var tm = traverser.transitionForEventNumber(log, event.eventNumber());
          if (tm == null) {
            throw new IllegalStateException(String.format(
                "No transition found for event number %d",
                event.eventNumber()
            ));
          }
          if (tm.reverseModel() != null) {
            finalBuilderFunction = chain.andThen(changeContext -> changeContext.flatMap(cc -> tm.reverseModel().calculateReverse(
                Mono.just(new InitialChangeContext<>(
                    cc,
                    null,
                    initialChangeContext.log(),
                    initialChangeContext.timestamp(),
                    initialChangeContext.correlationId(),
                    null,
                    initialChangeContext.stateMachine()
                ))
            )));
          }
        }

   */

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

    public <T1> WithOutgoingRequest<I, Void, O, T1> trigger(OutgoingRequestCreator<T1> outgoingRequestCreator) {
      return new WithOutgoingRequest<>(
          new TransitionModelBuilder<>(
              modelContext,
              initialChangeContext -> initialChangeContext.map(cc -> new AssembledChangeContext<>(cc, null))
          ),
          outgoingRequestCreator
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

      // TODO: Having this public to support reversible
      public TransitionModelBuilder<I, T, O> complete() {
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

      public TransitionModelBuilder<I, T, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
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

    public record WithIdentifier<I, T, O, I1, O1>(WithEntity<I, T, O, I1, O1> entity, List<Function<T, ? extends EntitySelector>> entitySelectors) {

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
//      public final WithIdentifier<I, T, O, I1, O1> identifiedBy(EntitySelector<T>... entitySelector) {
//        return new WithIdentifier<>(this, new ArrayList<>(List.of(entitySelector)));
//      }
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

  public <I1> TransitionModelBuilder<I, T, O> choice(List<GuardedTransition<T, I1, ?>> guardedTransitions, Function<T, I1> dataAdapter) {
    var builder = this;
    for (var guardedTransition : guardedTransitions) {
      builder = builder.when(guardedTransition, dataAdapter);
    }
    return builder;
  }

  public <I1, O1> TransitionModelBuilder<I, T, O> when(GuardedTransition<T, I1, O1> guardedTransition, Function<T, I1> dataAdapter) {
    return when(guardedTransition.guard()).then(guardedTransition.then(), dataAdapter);
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
                if (c instanceof ChoiceChangeContext) {
//                  log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + " skipped, another alternative already chosen");
                  return Mono.just(c);
                }
//                log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + ": testing with <" + c.stepOutput() + ">");
                if (!filter.predicate().test(c.stepOutput())) {
//                  log(modelContext, "Alternative " + filter.alternative().model().eventType().name() + " not matching");
                  return Mono.just(c);
                }
//                log(modelContext, "using alternative: " + filter.alternative().model().eventType().name());
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
                            null,
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
        new ModelContext<>(true /* !! */, null, BuiltinEventTypes.Rollback, List.of(), List.of(), List.of(), List.of(), null, null, List.of()),
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
        builderFunction
//            .andThen(changeContext -> changeContext.flatMap(c -> c.initialChangeContext()
//            .timestamp().isAfter(c.initialChangeContext().log().effectiveEvents().getLast().timestamp().plus(deadline)) ?
//            c.initialChangeContext().stateMachine().calculateChange(
//                eventType,
//                c.initialChangeContext().log(),
//                c.initialChangeContext().timestamp(),
//                c.initialChangeContext().correlationId()
//            ).map(output -> new ScheduledChangeContext<>(output, c)) : Mono.just(c)))
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

  public TransitionModelBuilder<I, T, O> reversible(TransitionModelBuilder<Data, ?, Data> reverseModel) {
    return new TransitionModelBuilder<>(
        new ModelContext<>(
            modelContext.reversal,
            modelContext.to,
            modelContext.eventType,
            modelContext.triggers,
            modelContext.outgoingRequests,
            modelContext.filters,
            modelContext.scheduledEvents,
            reverseModel.complete(),
            modelContext.rejectModel,
            modelContext.duplicateModels
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
                .contextWrite(ctx -> {
                  EventLog eventLog = c.initialChangeContext().log();
                  if (modelContext.eventType() == null) return ctx;
                  System.out.println("Session entity id for " + eventLog.entityModel().name() + ": " + eventLog.entityId().value());
                  return ctx.put(eventLog.entityModel(), eventLog.entityId());
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
            changeContext.initialChangeContext().stateMachine().createOutgoingRequest(
                modelContext.reversal,
                changeContext.initialChangeContext().timestamp(),
                new Entity(
                    changeContext.initialChangeContext().log().entityId(),
                    changeContext.initialChangeContext().log().secondaryIds(),
                    changeContext.initialChangeContext().log().entityModel()
                ),
                changeContext.stepOutput(),
                changeContext.initialChangeContext().eventNumber(),
                // TODO: This assumes nested change with parent entity is always calculated before outgoing request. Model does not restrict to that.
                //       Refactor as the concept is weak anyways.
                changeContext,
                requestModel,
                changeContext.initialChangeContext().correlationId()
                )
                .map(request -> new OutgoingRequestChangeContext<>(
                    changeContext.initialChangeContext().log().entityModel(),
                    changeContext.initialChangeContext().log().entityId(),
                    changeContext,
                    request
                ))
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

  /*
      public TransitionModel<I, O> output() {
      return new TransitionModel<>(
          modelContext,
          initialChangeContext -> initialChangeContext.map(i -> new OutputChangeContext<>(
              i,
              ProcessResult.accepted(
                  new Event<>(
                      i.log().entityId().value(),
                      i.log().lastEventNumber() + 1,
                      modelContext.eventType(),
                      i.timestamp()
                  ),
                  i.log().entityModel()
              )
          ))
      );
    }

   */

  private TransitionModel<I, O> complete() {
    return new TransitionModel<>(
        modelContext,
        builderFunction.andThen(a -> a.map(b -> new OutputChangeContext<>(
            b,
            ProcessResult.completed(
                b.initialChangeContext().log().entityId(),
                b.initialChangeContext().log().lastEventNumber() + 1,
                b.initialChangeContext().log().entityModel()
            ))))
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

//    public Mono<ChangeContext<O>> calculateReverse(
//        int eventNumber,
//        StateMachine stateMachine,
//        Clock clock,
//        ChangeContext<O> previous,
//        EventLog eventLog,
//        ZonedDateTime timestamp,
//        String correlationId
//    ) {
//
//      log(modelContext, "calculate reverse");
//      TransitionContext transitionContext = new TransitionContext(
//          null,
//          eventNumber,
//          timestamp,
//          correlationId,
//          stateMachine,
//          previous.stepOutput(),
//          eventLog
//      );
//      return calculateInternal(
//          transitionContext,
//          chain,
//          null,
//          clock
//      );
//    }

    public Mono<OutputChangeContext<O>> calculate(InitialChangeContext<I> initialChangeContext) {
      log(modelContext, "calculate [" + eventType().name() + "] on [" + initialChangeContext.log().entityModel().name() + "]");
      return chain.apply(Mono.just(initialChangeContext));
    }

    private Mono<OutputChangeContext<O>> calculateReverse(Mono<InitialChangeContext<I>> initial) {
      return chain.apply(initial);
    }

//    private Mono<ChangeContext<O>> calculateInternal(
//        TransitionContext transitionContext,
//        Function<TransitionContext, Mono<ChangeContext<O>>> builderFunction,
//        IncomingMessage incomingMessage,
//        Clock clock
//    ) {
//      return builderFunction.apply(transitionContext);
//      log(modelContext, "calculateInternal " + transitionContext.input().eventType().name());
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
//      return builderFunction.apply(transitionContext).map(finalChangeContext -> {
//            log(modelContext,
//                "calculate final " + transitionContext.input().eventType().name() + ": choice? "
//                    + finalChangeContext.isChoiceTransition()
//            );
//            Event<O> resultingEvent = modelContext.reversal() ? null : new Event<>(
//                transitionContext.log().entityId().value(),
//                transitionContext.log().lastEventNumber() + 1,
//                modelContext.eventType(),
//                clock,
//                finalChangeContext.stepOutput()
//            );
//          })
            /*
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
                       */
//      })
//          .onErrorResume(
//              // This occurs when the transition chain/function tries to access a ProcessResult on a rejected result
//              RejectedEvent.class,
//              e -> Mono.just(ChangeSet.empty(ProcessResult.rejected(modelContext.eventType(), e)))
//                  .doOnNext(cs -> System.out.println("RejectedEvent in calculateInternal for " + modelContext.eventType().name()))
//          )
//          .onErrorResume(
//              // This occurs when the transition chain/function tries to access a ProcessResult on a unknown id result
//              UnknownEntity.class,
//              e -> Mono.just(ChangeSet.empty(ProcessResult.unknownId(modelContext.eventType(), e)))
//          );
//    }

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
    System.out.println(modelContext.description() + ": " + text);
  }

}
