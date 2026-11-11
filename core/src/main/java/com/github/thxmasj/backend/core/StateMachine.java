package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.core.database.mssql.Mappers.eventTypesFor;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.EntitySelector.ById;
import com.github.thxmasj.backend.core.EntitySelector.ByIdFromSession;
import com.github.thxmasj.backend.core.EntitySelector.ByIdEvent;
import com.github.thxmasj.backend.core.EntitySelector.ByLastIndexed;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.IdExistsChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.PendingChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple3;
import com.github.thxmasj.backend.core.database.EntityGroupNotInitialised;
import com.github.thxmasj.backend.core.database.EventAlreadyExists;
import com.github.thxmasj.backend.core.database.IdEventAlreadyExists;
import com.github.thxmasj.backend.core.database.MappingFailure;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import com.github.thxmasj.backend.core.database.jdbc.JDBCClient;
import com.github.thxmasj.backend.core.database.mssql.ChangeState;
import com.github.thxmasj.backend.core.database.mssql.ChangeState.Change;
import com.github.thxmasj.backend.core.database.mssql.ConditionTriggers;
import com.github.thxmasj.backend.core.database.mssql.CreateSchema;
import com.github.thxmasj.backend.core.database.mssql.DeleteExpiredDeadline;
import com.github.thxmasj.backend.core.database.mssql.EventsByEntityId;
import com.github.thxmasj.backend.core.database.mssql.EventsByIdEvent;
import com.github.thxmasj.backend.core.database.mssql.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.database.mssql.NextDeadline;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;
import reactor.util.retry.RetrySpec;

public class StateMachine implements AutoCloseable {

  private static final int MAX_CONDITION_TRIGGERS_PER_POLL = 10;

  private final Function<List<String>, Mono<Void>> delayer;
  private final Clock clock;
  private final Listener listener;
  private final List<Looper<?>> workers = new ArrayList<>();
  private final DataSource dataSource;
  private final ChangeState changeState;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByIdEvent eventsByIdEvent;
  private final EventsByLastIndexEvent eventsByLastIndexEvent;
  private final NextDeadline nextDeadline;
  private final DeleteExpiredDeadline deleteExpiredDeadline;
  private final ConditionTriggers conditionTriggers;
  private final Map<EntityModel, Traverser> traversers;
  private final Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel;

  public StateMachine(
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      DataSource dataSource,
      String schemaName,
      boolean withResolving
  ) {
    this(null, transitions, dataSource, null, schemaName, null, Clock.systemUTC(), null, withResolving);
  }

  public StateMachine(
      Function<List<String>, Mono<Void>> delayer,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener
  ) {
    this(delayer, transitions, dataSource, schemaDataSource, schemaName, role, clock, listener, true);
  }

  public StateMachine(
      Function<List<String>, Mono<Void>> delayer,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener,
      boolean withResolving
  ) {
    System.out.println("Supported entity models:\n" + transitions.keySet().stream().map(EntityModel::name).collect(joining("\n")));
    this.traversers = transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> new Traverser(e.getValue())));
    List<EntityModel> entityModels = transitions.keySet().stream().toList();
    this.eventTypesByEntityModel = transitions.entrySet().stream()
        .collect(toMap(
            e -> e.getKey(),
            e -> Stream.concat(
                    BuiltinEventTypes.ALL.stream(),
                    e.getValue().values().stream().flatMap(tl -> tl.stream()).flatMap(t -> eventTypesFor(t).stream())
                )
                .distinct().toList()
        ));
    List<EventType<?, ?>> eventTypes =
        Stream.concat(
                BuiltinEventTypes.ALL.stream(),
                transitions.values()
                    .stream()
                    .flatMap(tl -> tl.values().stream())
                    .flatMap(tl -> tl.stream())
                    .flatMap(t -> eventTypesFor(t).stream())
            )
            .distinct()
            .toList();
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    if (schemaDataSource != null) {
      new CreateSchema(schemaName, role).execute(new JDBCClient(schemaDataSource))
          .timeout(Duration.ofSeconds(20), Mono.error(new RuntimeException("Schema creation timed out. Is the database running?")))
          .block();
    }
    this.clock = clock;
    this.dataSource = dataSource;
    var jdbcClient = new JDBCClient(dataSource);
    this.changeState = new ChangeState(jdbcClient, schemaName, clock);
    this.eventsByEntityId = new EventsByEntityId(dataSource, entityModels, schemaName, clock);
    this.eventsByIdEvent = new EventsByIdEvent(dataSource, eventTypesByEntityModel, schemaName, clock);
    this.eventsByLastIndexEvent = new EventsByLastIndexEvent(dataSource, schemaName, clock);
    this.nextDeadline = new NextDeadline(jdbcClient, eventTypes, clock, entityModels, schemaName);
    this.deleteExpiredDeadline = new DeleteExpiredDeadline(jdbcClient, schemaName);
    this.conditionTriggers = new ConditionTriggers(jdbcClient, eventTypesByEntityModel, clock, schemaName);
    this.listener = listener;
    if (withResolving) {
      Looper<ResolverStatus> resolverLooper = new Looper<>(
          "ResolverWorker",
          false,
          () -> resolveState().flux().switchIfEmpty(Flux.just(ResolverStatus.Empty)),
          status -> switch (status) {
            case Ok -> Duration.ZERO;
            case Empty -> ofSeconds(1);
            case Error -> ofSeconds(10);
          }
      );
      resolverLooper.loop();
      workers.add(resolverLooper);
      Looper<ResolverStatus> conditionTriggerLooper = new Looper<>(
          "ConditionTriggerWorker",
          false,
          () -> fireConditionTriggers().flux(),
          _ -> ofSeconds(1)
      );
      conditionTriggerLooper.loop();
      workers.add(conditionTriggerLooper);
    }
    System.out.println("Initialized " + getClass().getName() + " on database schema " + schemaName);
  }

  /// Stops the workers resolving deadlines and condition triggers, waits for their ongoing work to complete, and closes
  /// the data source if it is closeable, e.g. a connection pool. Requests should be stopped before this is called.
  @Override
  public void close() throws Exception {
    workers.forEach(Looper::stop);
    for (var worker : workers)
      if (!worker.awaitStopped(ofSeconds(30)))
        System.out.println("Gave up waiting for a worker to stop");
    if (dataSource instanceof AutoCloseable closeable)
      closeable.close();
  }

  private EventLog emptyEventLog(EntityModel entityModel) {
    return new EventLog(entityModel, newEntityId(), List.of());
  }

  private EventLog emptyEventLog(EntityModel entityModel, EntityId entityId) {
    return new EventLog(entityModel, entityId, List.of());
  }

  public enum ResolverStatus {Ok, Empty, Error}

  private <T> InputEvent<T> inputEvent(Deadline<T> deadline) {
    return new InputEvent<>(deadline.eventType(), deadline.data());
  }

  /**
   * Process the next delayed event that has reached its deadline.
   */
  public Mono<ResolverStatus> resolveState() {
    var backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);
    return nextDeadline.execute(backoff)
        .doOnNext(d -> System.out.println(ZonedDateTime.now() + ": Next deadline: " + d))
        .zipWhen(deadline -> eventsByEntityId.execute(deadline.entityModel(), eventTypesByEntityModel.get(deadline.entityModel()), deadline.entityId())
            .switchIfEmpty(Mono.error(new RuntimeException("Unknown entity " + deadline.entityId().value() + " for delayed event")))
        )
        .flatMap(deadlineAndEventLog -> {
          Deadline<?> deadline = deadlineAndEventLog.getT1();
          EventLog eventLog = deadlineAndEventLog.getT2();
          if (eventLog.events().getLast().eventNumber() + 1 != deadline.eventNumber()) {
            System.out.println("Deleting scheduled event: Event number (" + deadline.eventNumber() + ") != event log's last number (" + eventLog.events().getLast().eventNumber() + ") + 1");
            // Race! The state has already been resolved by another resolver or incoming request. Which is OK!
            return deleteExpiredDeadline.execute(deadline.entityId().value(), deadline.eventNumber()).map(_ -> ResolverStatus.Ok);
          }
          System.out.println("Deadline eventNumber: " + deadline.eventNumber() + ", log's last event number: "
              + eventLog.lastEventNumber());
          var currentState = traversers.get(deadline.entityModel()).currentState(eventLog);
          if (currentState == null)
            return Mono.error(new RuntimeException(
                "Invalid event log: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          InputEvent<?> event = inputEvent(deadline);
          System.out.println("resolveState with: " + event);
          return onEvent(
              deadline.correlationId(),
              event,
              eventLog,
              null
          )
              .flatMap(processResult -> switch (processResult) {
                    // State is resolved and deadline already deleted by the change triggered by this event.
                    case Accepted<?> _ -> Mono.just(ResolverStatus.Ok);
                    case Completed<?> _ -> Mono.just(ResolverStatus.Ok);
                    case ProcessResult<?> r -> Mono.error(new IllegalStateException("Unexpected result for state resolving: " + r));
                  }
              ).contextWrite(Correlation.contextOf(deadline.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(e -> { if (listener != null) listener.processNextDeadlineFailed(e); })
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

  public sealed interface ProcessResult<T> permits Accepted, Completed, IdExists, Pending, Rejected, UnknownId {

    record Completed<T>(ProcessResult<?> choiceResult, EntityId entityId, int eventNumber, EntityModel entityModel) implements ProcessResult<T> {
      public Completed<T> completed() {return this;}
    }

    /**
     * @param triggerType optional type of the stored trigger that caused the event
     * @param triggerId   id of the stored trigger that caused the event, present if and only if triggerType is
     */
    record Accepted<T>(Event<T> event, EntityModel entityModel, TriggerType triggerType, UUID triggerId) implements ProcessResult<T> {
      public Accepted<T> accepted() {return this;}
    }

    record Rejected<T>(EventType<?, T> eventType, RejectedEvent exception, AtomicBoolean handled) implements ProcessResult<T> {

      public Accepted<T> accepted() {
        System.out.println("accepted() called on ProcessResult.Rejected");
        throw exception;
      }

      public Rejected<T> rejected() {return this;}

      @Override
      public boolean isRejected() {
        handled.set(true);
        return true;
      }

      public boolean isHandled() {
        return handled.get();
      }
    }

    /// An id event not stored, as its id exists
    ///
    /// @param idEvent the id event not stored
    /// @param log     the event log of the entity having the id
    record IdExists<T>(EventType<?, T> eventType, Event<?> idEvent, EventLog log) implements ProcessResult<T> {

      public IdExists<T> idExists() {return this;}
    }

    record UnknownId<T>(EventType<?, T> eventType, UnknownEntity exception) implements ProcessResult<T> {

      public UnknownId<T> unknownId() {return this;}
    }

    record Pending<T, I, O>(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) implements ProcessResult<O> {

      @Override
      public Pending<?, ?, O> pending() {
        return this;
      }
    }

    static <T> Completed<T> completed(ProcessResult<?> choiceResult, EntityId entityId, int eventNumber, EntityModel entityModel) {
      return new Completed<>(choiceResult, entityId, eventNumber, entityModel);
    }

    default boolean isCompleted() {
      return this instanceof Completed;
    }

    default Completed<T> completed() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel) {
      return new Accepted<>(event, entityModel, null, null);
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel, TriggerType triggerType, UUID triggerId) {
      return new Accepted<>(event, entityModel, triggerType, triggerId);
    }

    default boolean isAccepted() {
      return this instanceof Accepted;
    }

    default Accepted<T> accepted() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> Rejected<T> rejected(EventType<?, T> eventType, RejectedEvent exception) {
      return new Rejected<>(eventType, exception, new AtomicBoolean());
    }

    default boolean isRejected() {
      return this instanceof Rejected;
    }

    default Rejected<T> rejected() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> IdExists<T> idExists(EventType<?, T> eventType, Event<?> idEvent, EventLog log) {
      return new IdExists<>(eventType, idEvent, log);
    }

    default boolean isIdExists() {
      return this instanceof IdExists;
    }

    default IdExists<T> idExists() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> UnknownId<T> unknownId(EventType<?, T> eventType, UnknownEntity exception) {
      return new UnknownId<>(eventType, exception);
    }

    default boolean isPending() {
      return this instanceof Pending;
    }

    default Pending<?, ?, T> pending() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T, I, O> Pending<T, I, O> pending(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) {
      return new Pending<>(trigger, inputData, exception);
    }

    default boolean isUnknownId() {
      return this instanceof UnknownId;
    }

    default UnknownId<T> unknownId() {
      return (UnknownId<T>) this;
    }

  }

  private Mono<EventLog> eventLogByEntityId(EntityModel entityModel, EntityId entityId) {
    return eventsByEntityId.execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId);
  }

  public <O> Flux<Event<?>> onEvent(EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger);
  }

  public <O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(correlationId, eventTrigger, null);
  }

  public <I, O> Flux<Event<?>> onEvent(EventTrigger<I, I, O> eventTrigger, I input) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger, input);
  }

  public <T, I, O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<T, I, O> eventTrigger, T input) {
    return onEvent(correlationId, eventTrigger, input, null);
  }

  private <T, I, O> Flux<Event<?>> onEvent(
      String correlationId,
      EventTrigger<T, I, O> eventTrigger,
      T input,
      @Nullable EventReference triggerEvent
  ) {
    System.out.println("onEvent " + correlationId + " " + eventTrigger.eventSpec().eventType().name());
    I adaptedInput = eventTrigger.eventSpec().inputAdapter().apply(input);
    Many<Event<?>> responseSink = Sinks.many().unicast().onBackpressureBuffer();
    return eventLog(eventTrigger, input, null)
        .flatMapMany(log ->
                onEvent(correlationId, eventTrigger.eventSpec().eventType(), adaptedInput, log, null, List.of(), eventTrigger.triggerType(), eventTrigger.triggerId(), triggerEvent)
                    .contextWrite(ctx -> ctx.put("RS/" + log.entityId().value(), responseSink))
                    .thenMany(responseSink.asFlux())
                    .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                        .filter(e -> e instanceof EventAlreadyExists)
                        // Avoid the "Thundering Herd" problem
                        .jitter(1.0)
                        .doAfterRetry(signal -> System.out.println(
                            System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to "
                                + signal.failure().getMessage()))
                        // Rethrow the exception on exhaustion so it can be handled downstream
                        .onRetryExhaustedThrow((_, signal) -> signal.failure())
                    )
                    .switchIfEmpty(Flux.error(new RuntimeException("onEvent: No response")))
        );
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      InputEvent<I> inputEvent,
      EventLog eventLog,
      ChangeContext<?> requestChangeContext
  ) {
    return onEvent(correlationId, inputEvent.eventType(), inputEvent.data(), eventLog, requestChangeContext, List.of());
  }

  private List<Change> changes(@NotNull ChangeContext<?> tail) {
    ArrayList<Change> changes = new ArrayList<>();
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case ChangeContext.OutputChangeContext<?> occ
            when occ.stepOutput().isAccepted() && !(occ.previous() instanceof ChangeContext.ChoiceChangeContext<?>) ->
            changes.add(Change.fromAcceptedEvent(occ.stepOutput().accepted()));
        case ChangeContext.OutputChangeContext<?> _ -> {}
        case ChangeContext.DelayedTriggerChangeContext<?, ?> dtcc -> changes.add(Change.fromDelayedEvent(dtcc.delayedEvent()));
        case ChangeContext.ConditionTriggerChangeContext<?, ?> ctcc -> changes.add(Change.fromConditionTrigger(ctcc.conditionTrigger()));
        case ChangeContext.AssembledChangeContext<?> _ -> {}
        case ChangeContext.ChoiceChangeContext<?> _ -> {}
        case ChangeContext.InitialChangeContext<?> _ -> {}
        case ChangeContext.PendingTriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.ScheduledChangeContext<?> _ -> {}
        case ChangeContext.TriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.PendingChangeContext<?, ?, ?> _ -> {}
        case ChangeContext.CombinedChangeContext<?, ?> _ -> {}
        case ChangeContext.RejectedChangeContext<?> _ -> {}
        case ChangeContext.UnknownIdChangeContext<?> _ -> {}
        case ChangeContext.IdExistsChangeContext<?> _ -> {}
        case ChangeContext.ActionChangeContext<?, ?> _ -> {}
      }
    }
    return changes;
  }

  private List<ActionTrigger<?>> actionTriggers(@NotNull ChangeContext<?> tail) {
    ArrayList<ActionTrigger<?>> actionTriggers = new ArrayList<>();
    OutputChangeContext<?> lastOutput = null;
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case OutputChangeContext.OutputChangeContext<?> occ -> lastOutput = occ;
        case ChangeContext.ActionChangeContext<?, ?> acc -> {
          if (lastOutput != null && lastOutput.stepOutput().isAccepted()) {
            actionTriggers.add(
                acc.actionTrigger().withEvent(lastOutput.stepOutput().accepted().event())
            );
          }
        }
        default -> {}
      }
    }
    return actionTriggers;
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      InputEvent<I> inputEvent,
      EventLog eventLog,
      ChangeContext<?> stage1,
      List<IdExists<?>> existingIds
  ) {
    return onEvent(correlationId, inputEvent.eventType(), inputEvent.data(), eventLog, stage1, existingIds);
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      EventType<I, ?> eventType,
      I input,
      EventLog eventLog,
      ChangeContext<?> stage1,
      List<IdExists<?>> existingIds
  ) {
    return onEvent(correlationId, eventType, input, eventLog, stage1, existingIds, null, null, null);
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      EventType<I, ?> eventType,
      I input,
      EventLog eventLog,
      ChangeContext<?> stage1,
      List<IdExists<?>> existingIds,
      TriggerType triggerType,
      UUID triggerId,
      @Nullable EventReference triggerEvent
  ) {
    System.out.println(eventLog.entityModel().name() + ": onEvent " + eventType.name() + " with log " + eventLog.events().stream().map(Event::typeName).collect(joining(",")));
    var now = ZonedDateTime.now(clock);
    var tuple = transitionModel(eventLog, eventType);
    if (tuple.t2() == null) {
      System.out.println("onEvent " + eventType.name() + ": No transition model found");
      return Mono.just(ProcessResult.rejected(
          eventType,
          new RejectedEvent(eventType, eventLog, tuple.t3())
      ));
    }
    if (tuple.t3() != null) {
      System.out.println("onEvent " + eventType.name() + ": Current state: " +  tuple.t3());
    }
    return tuple.t2().calculate(
            new InitialChangeContext<>(
                stage1,
                null,
                triggerEvent,
                tuple.t2(),
                tuple.t3(),
                eventLog.lastEventNumber() + 1,
                tuple.t1(),
                now,
                correlationId,
                input,
                this,
                existingIds,
                triggerType,
                triggerId
            )
        )
        .flatMap(this::calculatePendingChanges)
        .flatMap(output -> switch (finalResult(output)) {
          case Accepted<?> _, Completed<?> _ -> storeChanges(now, correlationId, output)
              .thenReturn(output.stepOutput());
          case Rejected<?> r -> tuple.t2().rejectModel() == null ? Mono.error(r.exception()) :
              tuple.t2().rejectModel().calculate(
                  new InitialChangeContext<>(
                      null,
                      null,
                      null,
                      tuple.t2(),
                      tuple.t3(),
                      eventLog.lastEventNumber() + 1,
                      tuple.t1(),
                      now,
                      correlationId,
                      tuple(
                          input,
                          eventLog.entityModel(),
                          eventType,
                          r.exception.getMessage()
                      ),
                      this,
                      List.of()
                  )
              )
              .flatMap(rejectChanges -> rejectChanges.stepOutput().isAccepted() ?
                  storeChanges(now, correlationId, rejectChanges).thenReturn(rejectChanges.stepOutput()) :
                  Mono.error(new RuntimeException(
                      "Failed to handle rejected event: " + rejectChanges.stepOutput() + " (original: "
                      + output.stepOutput() + ")"))
              );
          case ProcessResult<?> r -> Mono.error(new RuntimeException("onEvent: " + r));
        })
        // Calculates the changes again, with the id existing as the result of triggering the id event
        .onErrorResume(
            IdEventAlreadyExists.class,
            e -> eventsByIdEvent.execute(e.idEvent().type(), e.idEvent().getMarshalledData())
                .switchIfEmpty(Mono.error(e))
                .flatMap(log -> onEvent(
                    correlationId,
                    eventType,
                    input,
                    eventLog,
                    stage1,
                    Stream.concat(
                        existingIds.stream(),
                        Stream.of(ProcessResult.idExists(e.idEvent().type(), e.idEvent(), log))
                    ).toList(),
                    triggerType,
                    triggerId,
                    triggerEvent
                ))
        );
  }

  /// The existing id, if the triggered event is an id event with an id found to exist when storing the changes
  private <I, O> ChangeContext<ProcessResult<O>> idExists(EventTrigger<?, I, O> eventTrigger, ChangeContext<?> tail, I data) {
    if (!(eventTrigger.entityModel() instanceof IdEntityModel))
      return null;
    EventType<I, O> type = eventTrigger.eventSpec().eventType();
    return tail.firstInitialChangeContext().existingIds().stream()
        .filter(existing -> existing.idEvent().type().id().equals(type.id())
            && Arrays.equals(existing.idEvent().getMarshalledData(), type.inputDataType().marshal(data)))
        .findFirst()
        .map(existing -> new IdExistsChangeContext<>(tail, ProcessResult.idExists(type, existing.idEvent(), existing.log())))
        .orElse(null);
  }

  public static String chainToString(ChangeContext<?> tail) {
    StringBuilder b = new StringBuilder();
    for (ChangeContext<?> c = tail; c != null; c = c.previous()) {
      b.append(c).append("\n");
    }
    return b.toString();
  }

  private ProcessResult<?> finalResult(@NotNull ChangeContext<? extends ProcessResult<?>> tail) {
    for (ChangeContext<?> current = tail; current != null; current = current.previous()) {
      if (current instanceof RejectedChangeContext<?> p && !p.stepOutput().isHandled()) return p.stepOutput();
    }
    return tail.stepOutput();
  }

  private Mono<ChangeContext<? extends ProcessResult<?>>> calculatePendingChanges(@NotNull ChangeContext<? extends ProcessResult<?>> tail) {
    List<PendingChangeContext<?, ?, ?>> pendingChanges = new ArrayList<>();
    for (ChangeContext<?> current = tail; current != null; current = current.previous()) {
      if (current instanceof PendingChangeContext<?, ?, ?> p) pendingChanges.add(p);
    }
    Mono<ChangeContext<? extends ProcessResult<?>>> result = Mono.just(tail);
    for (var p : pendingChanges) {
      result = result.flatMap(r -> calculatePendingChange(p, r));
    }
    return result;
  }

  private <T, I, O> Mono<? extends ChangeContext<? extends ProcessResult<?>>> calculatePendingChange(PendingChangeContext<T, I, O> p, ChangeContext<?> previous) {
    return calculateTriggeredEvent(
        p.stepOutput().trigger(),
        previous,
        p.stepOutput().inputData(),
        true,
        new EventReference(p.initialChangeContext().log().entityId().value(), p.initialChangeContext().eventNumber())
    );
  }


  public Mono<State> onStatus(EntitySelector entitySelector, EntityModel entityModel) {
    return eventLog(entitySelector, entityModel, null)
        .map(log -> traversers.get(entityModel).currentState(log));
  }

  private <T> Flux<Event<?>> fireConditionTrigger(ConditionTrigger<T> conditionTrigger) {
    return fireConditionTrigger(conditionTrigger, conditionTrigger.type());
  }

  private <T, O> Flux<Event<?>> fireConditionTrigger(
      ConditionTrigger<T> conditionTrigger,
      EventType<T, O> eventType
  ) {
    var eventTrigger = new EventTrigger<Void, T, O>(
        new EventTrigger.EventSpec<>(eventType, _ -> conditionTrigger.data()),
        // The target entity may not exist yet, in which case it is created by the fired event
        List.of(_ -> EntitySelector.entityId(conditionTrigger.entityId(), EntitySelector.CreationMode.CreateIfNotExists)),
        conditionTrigger.entityModel(),
        false,
        null,
        TriggerType.CONDITION,
        conditionTrigger.id()
    );
    // The condition trigger is deleted together with the change of the fired event
    return onEvent(conditionTrigger.correlationId(), eventTrigger, null, conditionTrigger.triggerEvent())
        .contextWrite(Correlation.contextOf(conditionTrigger.correlationId()));
  }

  /**
   * Fire waiting condition triggers whose condition is now met. The waiting row is deleted together with the change
   * of the fired event. Returns Ok if an event was fired, otherwise Empty (or Error).
   */
  public Mono<ResolverStatus> fireConditionTriggers() {
    System.out.println("fireConditionTriggers()");
    return conditionTriggers.execute(MAX_CONDITION_TRIGGERS_PER_POLL)
        .doOnNext(t -> System.out.println("fireConditionTriggers() found trigger: " + t))
        .concatMap(event -> fireConditionTrigger(event)
            .hasElements()
            .onErrorResume(e -> {
              e.printStackTrace();
              System.out.println("Failed to fire condition trigger " + event.type().name() + " on " + event.entityId() + ": " + e);
              return Mono.empty();
            })
        )
        .any(fired -> fired)
        .map(fired -> fired ? ResolverStatus.Ok : ResolverStatus.Empty)
        .onErrorReturn(ResolverStatus.Error);
  }

  private <T, I, O> Mono<EventLog> eventLog(
      EventTrigger<T, I, O> eventTrigger,
      T inputData,
      ChangeContext<?> changeContext
  ) {
    return eventTrigger.createEntity() ?
        Mono.just(emptyEventLog(eventTrigger.entityModel())) :
        eventLog(eventTrigger.entitySelectors().getFirst().apply(inputData), eventTrigger.entityModel(), changeContext);
  }

  private <I> Mono<EventLog> eventLog(
      EntitySelector entitySelector,
      EntityModel entityModel,
      @Nullable ChangeContext<?> changeContext
  ) {
    return switch (entitySelector) {
      case EntitySelector.ByIdFromSession _ -> {
        EventLog log = logFromChangeContext(changeContext, entityModel);
        EntityId id = log.entityId();
        log = logFromNestedChanges(id, entityModel, changeContext);
        if (log == null) yield eventLogByEntityId(entityModel, id);
        else yield Mono.just(log);
      }
      case ById s -> {
        EntityId entityId = s.id();
        yield switch (s.creationMode()) {
          case NeverCreate -> Mono.justOrEmpty(logFromNestedChanges(entityId, entityModel, changeContext))
              .switchIfEmpty(eventsByEntityId.execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId));
          case CreateIfNotExists -> Mono.justOrEmpty(logFromNestedChanges(entityId, entityModel, changeContext))
              .switchIfEmpty(eventsByEntityId.execute(entityModel, eventTypesByEntityModel.get(entityModel), entityId))
              .onErrorResume(UnknownEntity.class, _ -> Mono.just(emptyEventLog(entityModel, entityId)));
          case AlwaysCreate -> Mono.just(emptyEventLog(entityModel, s.id()));
        };
      }
      case ByIdEvent<?> s -> eventLog(s, entityModel, changeContext);
      case ByLastIndexed<?> s -> eventLog(s, entityModel);
      case EntitySelector s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private <T> Mono<EventLog> eventLog(ByIdEvent<T> selector, EntityModel entityModel, ChangeContext<?> changeContext) {
    Mono<EventLog> log = eventsByIdEvent.execute(selector.type(), selector.type().outputDataType().marshal(selector.id()))
        .filter(l -> l.entityModel().equals(entityModel));
    return switch (selector.creationMode()) {
      case NeverCreate -> log.switchIfEmpty(Mono.defer(() -> selector.fallback() != null ?
          eventLog(selector.fallback(), entityModel, changeContext) :
          Mono.error(new UnknownEntity(entityModel, selector.type(), selector.id()))
      ));
      case CreateIfNotExists -> log.switchIfEmpty(Mono.fromSupplier(() -> emptyEventLog(entityModel)));
      case AlwaysCreate -> Mono.just(emptyEventLog(entityModel));
    };
  }

  @SuppressWarnings("unchecked")
  private <G> Mono<EventLog> eventLog(ByLastIndexed<G> selector, EntityModel entityModel) {
    if (!(selector.type().outputDataType() instanceof GroupedDataType<?, ?> dataType))
      throw new IllegalArgumentException("Data type of index event type " + selector.type().name() + " is not grouped");
    Mono<EventLog> log = eventsByLastIndexEvent.execute(
        entityModel,
        eventTypesByEntityModel.get(entityModel),
        selector.type().id(),
        ((GroupedDataType<?, G>) dataType).marshalGroup(selector.group()),
        selector.position()
    );
    return switch (selector.creationMode()) {
      case NeverCreate -> log.switchIfEmpty(Mono.error(() -> new EntityGroupNotInitialised(entityModel, selector.type(), selector.group())));
      case CreateIfNotExists -> log.switchIfEmpty(Mono.fromSupplier(() -> emptyEventLog(entityModel)));
      case AlwaysCreate -> throw new IllegalStateException("Unexpected value: " + selector.creationMode());
    };
  }

  private EventLog logFromChangeContext(ChangeContext<?> context, EntityModel entityModel) {
    for (var c = context; c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i && i.log().entityModel().equals(entityModel)) {
        return i.log();
      }
    }
    InitialChangeContext<?> initial = null;
    for (var c = context; c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i) initial = i;
    }
    for (var c = initial.stage1(); c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i && i.log().entityModel().equals(entityModel)) {
        return i.log();
      }
    }
    throw new NoSuchElementException(entityModel.name() + " not found in change context");
  }

  /// An id entity or index entity has the same entity id as its entity, so the entity model is matched too
  private EventLog logFromNestedChanges(EntityId entityId, EntityModel entityModel, ChangeContext<?> changeContext) {
    for (var c = changeContext; c != null; c = c.previous()) {
      if (c instanceof OutputChangeContext<?>(TransitionModelBuilder.ChangeContext<?> previous, ProcessResult<?> stepOutput)
          && stepOutput instanceof Accepted<?>(Event<?> event, EntityModel model, _, _)
          && event.entityId().equals(entityId.value())
          && model.equals(entityModel)
          && !(previous instanceof ChoiceChangeContext)) {
        return c.initialChangeContext().log().withNewEvent(event);
      }
    }
    return null;
  }

  private void checkCircular(ChangeContext<?> tail, EntityModel entityModel, EntityId entityId, InputEvent<?> inputEvent) {
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case ChangeContext.OutputChangeContext<?> o
            when o.stepOutput().isAccepted() && o.stepOutput().accepted().entityModel().equals(entityModel): return;
        case ChangeContext.InitialChangeContext<?> i when i.log().entityModel().equals(entityModel):
          throw new CircularChange(inputEvent, entityModel, entityId);
        default:
      }
    }
  }

  public <T, I, O> Mono<ChangeContext<ProcessResult<O>>> calculateTriggeredEvent(
      EventTrigger<T, I, O> eventTrigger,
      ChangeContext<?> tail,
      T data,
      boolean skipCircularCheck,
      EventReference triggerEvent
  ) {
    I adaptedData = eventTrigger.eventSpec().inputAdapter() != null ? eventTrigger.eventSpec().inputAdapter().apply(data) : null;
    ChangeContext<ProcessResult<O>> idExists = idExists(eventTrigger, tail, adaptedData);
    if (idExists != null)
      return Mono.just(idExists);
    return Mono.deferContextual(ctx -> {
          EntityId entityId = switch (eventTrigger.entitySelectors().getFirst().apply(data)) {
            case ByIdFromSession _ -> {
              try {
                yield logFromChangeContext(tail, eventTrigger.entityModel()).entityId();
              } catch (NoSuchElementException e) {
                throw new RuntimeException(e.getMessage() + "\nFound in Reactor context:\n" +
                    ctx.stream().map(x -> x.toString()).collect(joining("\n")));
              }
            }
            case ById s -> s.id();
            default -> null;
          };
          if (!skipCircularCheck && entityId != null) {
            checkCircular(tail, eventTrigger.entityModel(), entityId, new InputEvent<>(eventTrigger.eventSpec().eventType(), adaptedData));
          }
          return Mono.just(ctx);
        }
    ).flatMap(_ -> eventLog(eventTrigger, data, tail)
            .map(eventLog -> transitionModel(eventLog, eventTrigger.eventSpec().eventType()))
            .map(tuple -> {
              if (tuple.t2() != null)
                return tuple;
              throw new RejectedEvent(
                  eventTrigger.eventSpec().eventType(),
                  tuple.t1(),
                  tuple.t3()
              );
            })
            .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                .filter(e -> e instanceof RejectedEvent)
                // Avoid the "Thundering Herd" problem
                .jitter(1.0)
                .doAfterRetry(signal -> System.out.println(
                    System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to " + signal.failure()
                        .getMessage()))
                // Rethrow the exception on exhaustion so it can be handled downstream
                .onRetryExhaustedThrow((_, signal) -> signal.failure())
            )
            .flatMap(tuple -> tuple.t2().calculate(
                new InitialChangeContext<>(
                    null,
                    tail,
                    triggerEvent,
                    tuple.t2(),
                    tuple.t3(),
                    tuple.t1().lastEventNumber() + 1,
                    tuple.t1(),
                    tail.initialChangeContext().timestamp(),
                    tail.initialChangeContext().correlationId(),
                    adaptedData,
                    tail.initialChangeContext().stateMachine(),
                    List.of()
                )
            ))
    );
  }

  public static class CircularChange extends RuntimeException {

    private final EntityModel entityModel;
    private final EntityId entityId;

    public CircularChange(InputEvent<?> inputEvent, EntityModel entityModel, EntityId entityId) {
      super(String.format(
          "%s on %s/id=%s is circular",
          inputEvent.eventType().name(),
          entityModel.name(),
          entityId.value().toString()
      ));
      this.entityModel = entityModel;
      this.entityId = entityId;
    }

    public EntityModel entityModel() {
      return entityModel;
    }

    public EntityId entityId() {
      return entityId;
    }
  }

  public static class RejectedEvent extends RuntimeException {

    private final EventType<?, ?> eventType;
    private final EntityModel entityModel;
    private final EntityId entityId;
    private final State currentState;

    private static String prefixMessage(EventType<?, ?> eventType, EntityModel entityModel, EntityId entityId) {
      return String.format(
          "[%s] on [%s]/%s rejected",
          eventType != null ? eventType.name() : "<no event type>",
          entityModel != null ? entityModel.name() : "<no entity model>",
          entityId != null ? entityId.value() : "<no entity id>"
      );
    }

    public RejectedEvent(EventType<?, ?> eventType, EventLog eventLog, State currentState) {
      super(String.format(
          "%s for state [%s] (%s)",
          prefixMessage(eventType, eventLog.entityModel(), eventLog.entityId()),
          currentState != null ? currentState.name() : "<no current state>",
          eventLog.events().stream().map(e -> e.type().name()).collect(joining(", "))
      ));
      this.eventType = eventType;
      this.entityModel = eventLog.entityModel();
      this.entityId = eventLog.entityId();
      this.currentState = currentState;
    }

    public RejectedEvent(
        EventType<?, ?> eventType,
        EntityModel entityModel,
        EntityId entityId,
        State currentState,
        int rollbackTo
    ) {
      super(String.format(
          "%s for state [%s]: Can't rollback to event number %d",
          prefixMessage(eventType, entityModel, entityId),
          currentState != null ? currentState.name() : "<no current state>",
          rollbackTo
      ));
      this.eventType = eventType;
      this.entityModel = entityModel;
      this.entityId = entityId;
      this.currentState = currentState;
    }

    public EventType<?, ?> eventType() {
      return eventType;
    }

    public EntityModel entityModel() {
      return entityModel;
    }

    public EntityId entityId() {
      return entityId;
    }
  }

  private <I, O> Tuple3<EventLog, TransitionModel<I, O>, State> transitionModel(
      EventLog eventLog,
      EventType<I, O> eventType
  ) {
    var traverser = traversers.get(eventLog.entityModel());
    if (traverser == null) throw new IllegalStateException("No traverser for " + eventLog.entityModel().name());
    var currentStateAndTransition = traverser.accept(eventLog, eventType);
    return tuple(eventLog, (TransitionModel<I, O>) currentStateAndTransition.t2(), currentStateAndTransition.t1());
  }

  private List<Listener.Change> toListenerFormat(List<Change> changes) {
    return changes.stream()
        .map(change -> new Listener.Change(
                new Listener.Change.Entity(
                    change.entityModel().name(),
                    change.entityId().value()
                ),
                change.newEvent() != null ? new Listener.Change.Event(
                    change.newEvent().eventNumber(),
                    change.newEvent().type().name()
                ) : (change.delayedEvent() != null ? new Listener.Change.DelayedEvent(
                    change.delayedEvent().eventNumber(),
                    change.delayedEvent().type().name(),
                    change.delayedEvent().after()
                ) : null)
            )
        )
        .toList();
  }

  private Mono<Void> storeChanges(
      ZonedDateTime timestamp,
      String correlationId,
      ChangeContext<?> changeContext
  ) {
    List<Change> changes = changes(changeContext);
    System.out.println("Changes to store:\n" + changes.stream().map(c -> "  |" + c.toString()).collect(joining("\n")));
    return Mono.just("")
        .delayUntil(_ -> delayer.apply(changes.stream()
            .filter(change -> change.newEvent() != null)
            .map(change -> correlationId + "-" + change.newEvent().typeName())
            .toList())
        )
        .flatMap(_ -> changeState.execute(
                    timestamp,
                    correlationId,
                    changes.stream()
                        .filter(c -> c.newEvent() == null || !(c.newEvent().type() instanceof BasicEventType.ReadOnly))
                        .toList()
                )
                .collectList()
                .flatMap(x -> Mono.just("")
                    .contextWrite(ctx -> {
                          changes.stream()
                              .filter(change -> change.newEvent() != null)
                              .collect(Collectors.groupingBy(
                                  Change::entityId,
                                  toList()
                              ))
                              .forEach((entityId, changeList) -> {
                                changeList.sort(comparing(change -> change.newEvent().eventNumber()));
                                Optional<Many<Event<?>>> responseSink = ctx.getOrEmpty("RS/" + entityId.value());
                                if (responseSink.isPresent()) {
                                  System.out.println("Found response sink for " + changeList.stream().map(c -> c.entityModel().name() + "/" + c.entityId().value() + "/" + c.newEvent().typeName()).toList());
                                  changeList.forEach(change -> responseSink.get().tryEmitNext(change.newEvent()).orThrow());
                                }
                              });
                          return ctx;
                        }
                    ).thenReturn(x)
                )
                .doOnNext(_ -> {
                  if (!changes.isEmpty() && listener != null) {
                    listener.changeAccepted(correlationId, toListenerFormat(changes));
                  }
                })
                // Forward outgoing requests (for guaranteed delivery this will be the first attempt)
                .transformDeferredContextual((publisher, ctx) -> publisher
                    .doOnNext(_ -> actionTriggers(changeContext).forEach(actionTrigger ->
                        triggerAction(actionTrigger, correlationId, changeContext)
                            .contextWrite(ctx).subscribe()))
                )
                .then()
        );
  }

  private <T> Mono<ProcessResult<?>> triggerAction(ActionTrigger<T> actionTrigger, String correlationId, ChangeContext<?> stage1) {
    return actionTrigger.trigger()
        .flatMap(triggerResult -> onEvent(
                correlationId,
                triggerResult,
                actionTrigger.eventLog(),
                stage1,
                List.of()
            )
            .doOnError(e -> System.out.println("onEvent ERROR for " + triggerResult.eventType().name() + " on " + actionTrigger.eventLog().entityModel().name() + ":" + actionTrigger.eventLog().entityId().value() + ": " + e))
            .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
                .filter(e -> e instanceof EventAlreadyExists)
                // Avoid the "Thundering Herd" problem
                .jitter(1.0)
                .doAfterRetry(signal -> System.out.println(
                    System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to "
                        + signal.failure().getMessage()))
                // Rethrow the exception on exhaustion so it can be handled downstream
                .onRetryExhaustedThrow((_, signal) -> signal.failure())
            )

        );
  }

  private EntityId newEntityId() {
    return new EntityId.UUID(UUID.randomUUID());
  }

  public Traverser traverser(EntityModel entityModel) {
    return traversers.get(entityModel);
  }

}
