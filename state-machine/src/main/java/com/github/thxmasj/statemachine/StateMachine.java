package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EntitySelector.ById;
import com.github.thxmasj.statemachine.EntitySelector.ByIdFromSession;
import com.github.thxmasj.statemachine.EntitySelector.ByLastInIdGroup;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Context;
import com.github.thxmasj.statemachine.OutboxWorker.ForwardStatus;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.PendingChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.database.EntityGroupNotInitialised;
import com.github.thxmasj.statemachine.database.EventAlreadyExists;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.SecondaryIdAlreadyExists;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCClient;
import com.github.thxmasj.statemachine.database.mssql.ChangeState;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.CreateSchema;
import com.github.thxmasj.statemachine.database.mssql.EventsByEntityId;
import com.github.thxmasj.statemachine.database.mssql.EventsByLastEntity;
import com.github.thxmasj.statemachine.database.mssql.EventsByLookupId;
import com.github.thxmasj.statemachine.database.mssql.LastSecondaryId;
import com.github.thxmasj.statemachine.database.mssql.Mappers;
import com.github.thxmasj.statemachine.database.mssql.MoveToDLQ;
import com.github.thxmasj.statemachine.database.mssql.NextDeadline;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

public class StateMachine {

  private final Function<List<String>, Mono<Void>> delayer;
  private final Clock clock;
  private final Listener listener;
  @SuppressWarnings("ALL")
  private final List<Looper<?>> workers = new ArrayList<>();
  private final ChangeState changeState;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByLookupId eventsByLookupId;
  private final EventsByLastEntity eventsByLastEntity;
  private final LastSecondaryId lastSecondaryId;
  private final MoveToDLQ moveToDLQ;
  private final NextDeadline nextDeadline;
  private final Map<EntityModel, Traverser> traversers;

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
    this.traversers = transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> new Traverser(e.getValue())));
    List<EntityModel> entityModels = transitions.keySet().stream().toList();
    Map<EntityModel, List<EventType<?, ?>>> eventTypes = transitions.entrySet().stream()
        .collect(toMap(
            Entry::getKey,
            e -> e.getValue().values().stream()
                .flatMap(Collection::stream)
                .flatMap(t -> Stream.concat(
                    Mappers.eventTypesFor(t).stream(),
                    BuiltinEventTypes.ALL.stream()
                ))
                .distinct()
                .toList()
        ));
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    if (schemaDataSource != null) {
      new CreateSchema(entityModels, schemaName, role).execute(new JDBCClient(schemaDataSource))
          .timeout(Duration.ofSeconds(5), Mono.error(new RuntimeException("Schema creation timed out. Is the database running?")))
          .block();
    }
    this.clock = clock;
    var jdbcClient = new JDBCClient(dataSource);
    this.changeState = new ChangeState(entityModels, jdbcClient, schemaName, clock);
    var eventMappers = eventTypes.entrySet().stream().collect(toMap(Entry::getKey, e -> Mappers.eventMapper(e.getValue(), clock)));
    this.eventsByEntityId = new EventsByEntityId(dataSource, entityModels, schemaName, eventMappers);
    this.eventsByLookupId = new EventsByLookupId(dataSource, entityModels, schemaName, eventMappers);
    this.eventsByLastEntity = new EventsByLastEntity(
        dataSource,
        entityModels,
        schemaName,
        eventTypes.entrySet().stream().collect(toMap(Entry::getKey, e -> Mappers.eventTypeMapper(e.getValue()))),
        clock
    );
    this.lastSecondaryId = new LastSecondaryId(dataSource, entityModels, schemaName);
    this.moveToDLQ = new MoveToDLQ(jdbcClient, schemaName);
    this.nextDeadline = new NextDeadline(jdbcClient, clock, entityModels, schemaName);
    this.listener = listener;
    // TODO: Differentiate delay spec per queue
//    var backoff = new DelaySpecification(ofSeconds(10), ofSeconds(20), ofSeconds(100), 1.5);
//    var processBackedOff = new ProcessBackedOff(jdbcClient, schemaName, clock, backoff);
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
//    for (var entityModel : entityModels) {
//      if (entityModel instanceof HttpOutbox.EntityModel queue) {
//        var looper = new OutboxWorker(
//            this,
//            processBackedOff,
//            listener,
//            queue,
//            clock
//        ).forwarder(true);
//        looper.loop();
//        workers.add(looper);
//      }
//    }
    System.out.println("Initialized " + getClass().getName() + " on database schema " + schemaName);
  }

  private static List<TransitionModel<?, ?>> unnest(TransitionModel<?, ?> model) {
    return Stream.concat(
            model.reverseModel() != null ? Stream.of(model, model.reverseModel()) : Stream.of(model),
            model.filters().stream().flatMap(f -> unnest(f.alternative().model()).stream())
        )
        .toList();
  }

  private EventLog emptyEventLog(EntityModel entityModel) {
    return new EventLog(entityModel, newEntityId(), List.of(), List.of());
  }

  private EventLog emptyEventLog(EntityModel entityModel, EntityId entityId) {
    return new EventLog(entityModel, entityId, List.of(), List.of());
  }

  public enum ResolverStatus {Ok, Empty, Error}

  /**
   * Resolve a state that has reached its deadline, as indicated by its timeout value.
   */
  public Mono<ResolverStatus> resolveState() {
    var backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);
    return nextDeadline.execute(backoff)
        .doOnNext(d -> System.out.println(ZonedDateTime.now() + ": Next deadline: " + d))
        .zipWhen(deadline -> eventsByEntityId.execute(deadline.entityModel(), deadline.entityId()))
        .flatMap(deadlineAndEventLog -> {
          Deadline deadline = deadlineAndEventLog.getT1();
          EventLog eventLog = deadlineAndEventLog.getT2();
          if (eventLog.events().getLast().eventNumber() != deadline.eventNumber()) {
            // Race! The state has already been resolved by another resolver or incoming request. Which is OK!
            return Mono.just(ResolverStatus.Ok);
          }
          System.out.println("Deadline eventNumber: " + deadline.eventNumber() + ", log's last event number: "
              + eventLog.lastEventNumber());
          var currentState = traversers.get(deadline.entityModel()).currentState(eventLog);
          if (currentState == null)
            return Mono.error(new RuntimeException(
                "Invalid event log: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          InputEvent<?> event = currentState.timeout().event(deadline.eventNumber());
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
                    // State is resolved and deadline already deleted by the change triggered by the racing event.
                    //case Raced<?> _ -> Mono.just(ResolverStatus.Ok);
                    // Need to retry. Deadline was already modified when reading.
//                    case Failed<?> r -> {
//                      listener.resolveStateFailed(
//                          deadline.correlationId(),
//                          eventLog.entityId(),
//                          currentState.name(),
//                          event.eventType(),
//                          r.reason()
//                      );
//                      yield backoff.isExhausted(eventLog.events().getLast().timestamp(), deadline.nextAttemptAt(), clock) ?
//                          Mono.error(new RuntimeException("Period for state resolving exhausted: " + Duration.between(
//                              eventLog.events()
//                              .getLast()
//                              .timestamp(), ZonedDateTime.now(clock)
//                          ))) :
//                          Mono.just(ResolverStatus.Ok);
//                    }
                    // This is a bug.
                    // - Rejection should not happen unless model is wrong. TODO: sanitize
                    case ProcessResult<?> r -> Mono.error(new IllegalStateException(
                        "Unexpected result for state resolving: " + r.getClass().getSimpleName()));
                  }
              ).contextWrite(Correlation.contextOf(deadline.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(listener::processNextDeadlineFailed)
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

//  private Mono<Result> validateResponse(
//      HttpRequestMessage requestMessage,
//      HttpResponseMessage responseMessage,
//      IncomingResponseValidator<?> validator,
//      EntityId entityId,
//      int currentEventNumber,
//      int startOfSessionEventNumber
//  ) {
//    return validator.execute(
//        entityId,
//        new IncomingResponseContext<>(currentEventNumber, startOfSessionEventNumber),
//        requestMessage,
//        new Input.IncomingResponse(
//            responseMessage,
//            currentEventNumber
//        )
//    );
//  }

  private static class IncomingResponseContext<DATA_TYPE> implements Context<DATA_TYPE> {

    private final int currentEventNumber;
    private final int startOfSessionEventNumber;

    private IncomingResponseContext(int currentEventNumber, int startOfSessionEventNumber) {
      this.currentEventNumber = currentEventNumber;
      this.startOfSessionEventNumber = startOfSessionEventNumber;
    }

    @Override
    public InputEvent<String> requestUndelivered(String cause) {
      return new InputEvent<>(BuiltinEventTypes.RequestUndelivered, cause);
    }

    @Override
    public InputEvent<DATA_TYPE> validResponse(EventType<DATA_TYPE, ?> eventType, DATA_TYPE data) {
      return new InputEvent<>(eventType, data);
    }

    @Override
    public InputEvent<String> invalidResponse(String cause) {
      return new InputEvent<>(BuiltinEventTypes.InvalidResponse, cause);
    }

    @Override
    public InputEvent<Data> rollback(String cause) {
      return new InputEvent<>(
          Rollback, new Data(startOfSessionEventNumber - 1, currentEventNumber - 1, "Response validator triggered rollback of session (starting with event number " + startOfSessionEventNumber + "): " + cause)
      );
    }
  }

  public sealed interface IdentityResult<T> permits IdentityResult.Accepted, IdentityResult.Rejected {

    record Accepted<T>(SecondaryId<T> id) implements IdentityResult<T> {
      public Accepted<T> accepted() {return this;}
    }

    record Rejected<T>(SecondaryId<T> id, EventLog log) implements IdentityResult<T> {
      public Rejected<T> rejected() {return this;}
    }

    static <T> Accepted<T> accepted(SecondaryId<T> id) {
      return new Accepted<>(id);
    }

    static <T> Rejected<T> rejected(SecondaryId<T> id, EventLog log) {
      return new Rejected<>(id, log);
    }

    SecondaryId<T> id();

    default boolean isAccepted() {
      return this instanceof Accepted;
    }

    default Accepted<T> accepted() {
      throw new IllegalStateException("Result is " + this);
    }

    default boolean isRejected() {
      return this instanceof Rejected;
    }

    default Rejected<T> rejected() {
      throw new IllegalStateException("Result is " + this);
    }

  }

  public sealed interface ProcessResult<T> permits Accepted, Completed, Pending, Rejected, UnknownId {

    record Completed<T>(ProcessResult<?> choiceResult, EntityId entityId, int eventNumber, EntityModel entityModel) implements ProcessResult<T> {
      public Completed<T> completed() {return this;}
    }

    record Accepted<T>(Event<T> event, EntityModel entityModel, Duration timeout) implements ProcessResult<T> {
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

    record UnknownId<T>(EventType<?, T> eventType, UnknownEntity exception) implements ProcessResult<T> {

      public UnknownId<T> unknownId() {return this;}
    }

    record Pending<T, I, O>(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) implements ProcessResult<O> {

      @Override
      public Pending<?, ?, O> pending() {
        return this;
      }
    }

    record Entity(
        EntityId id,
        List<SecondaryId<?>> secondaryIds,
        EntityModel model
    ) {}

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
      return new Accepted<>(event, entityModel, null);
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel, Duration timeout) {
      return new Accepted<>(event, entityModel, timeout);
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
    return eventsByEntityId.execute(entityModel, entityId);
  }

  public <T> Mono<SecondaryId<T>> next(SecondaryIdModel<T> idModel, Object idGroup) {
    return lastSecondaryId.execute(idModel, idGroup)
        .map(id -> id.model().group().next(id))
        .switchIfEmpty(Mono.just(idModel.group().initial(idGroup)));
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
    System.out.println("onEvent " + correlationId + " " + eventTrigger.eventSpec().eventType().name());
    I adaptedInput = eventTrigger.eventSpec().inputAdapter().apply(input);
    Many<Event<?>> responseSink = Sinks.many().unicast().onBackpressureBuffer();
    return eventLog(eventTrigger, input, null)
        .flatMapMany(log ->
                onEvent(correlationId, eventTrigger.eventSpec().eventType(), adaptedInput, log, null, List.of())
                    .contextWrite(ctx -> ctx.put("RS/" + log.entityId().value(), responseSink))
                    .thenMany(responseSink.asFlux())
                    .doOnNext(e -> System.out.println("onEvent output: " + e.type().name() + " (#" + e.eventNumber() + ")"))
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
        case ChangeContext.AssembledChangeContext<?> _ -> {}
        case ChangeContext.ChoiceChangeContext<?> _ -> {}
        case ChangeContext.InitialChangeContext<?> _ -> {}
        case ChangeContext.IdentityChangeContext<?> icc when icc.stepOutput().isAccepted() -> changes.add(Change.fromIdentifier(icc.stepOutput().accepted().id(), icc.entityModel(), icc.entityId()));
        case ChangeContext.IdentityChangeContext<?> _ -> {}
        case ChangeContext.PendingTriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.ScheduledChangeContext<?> _ -> {}
        case ChangeContext.TriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.PendingChangeContext<?, ?, ?> _ -> {}
        case ChangeContext.CombinedChangeContext<?, ?> _ -> {}
        case ChangeContext.RejectedChangeContext<?> _ -> {}
        case ChangeContext.UnknownIdChangeContext<?> _ -> {}
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
      List<IdentityResult<?>> identityResults
  ) {
    return onEvent(correlationId, inputEvent.eventType(), inputEvent.data(), eventLog, stage1, identityResults);
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      EventType<I, ?> eventType,
      I input,
      EventLog eventLog,
      ChangeContext<?> stage1,
      List<IdentityResult<?>> identityResults
  ) {
    System.out.println("onEvent " + eventType.name() + " with log " + eventLog.events().stream().map(Event::typeName).collect(joining(",")));
    var now = ZonedDateTime.now(clock);
    var tuple = transitionModel(eventLog, eventType);
    if (tuple.t2() == null) {
      System.out.println("onEvent " + eventType.name() + ": No transition model found");
      return Mono.just(ProcessResult.rejected(
          eventType,
          new RejectedEvent(eventType, eventLog.entityModel(), eventLog.entityId(), tuple.t3())
      ));
    }
    if (tuple.t3() != null) {
      System.out.println("onEvent " + eventType.name() + ": Current state: " +  tuple.t3());
    }
    return tuple.t2().calculate(
            new InitialChangeContext<>(
                stage1,
                null,
                tuple.t2(),
                tuple.t3(),
                eventLog.lastEventNumber() + 1,
                tuple.t1(),
                now,
                correlationId,
                input,
                this,
                identityResults
            )
        )
        .doOnNext(output -> System.out.printf("onEvent result with pending unresolved:\n" + chainToString(output)))
        .flatMap(this::calculatePendingChanges)
        .doOnNext(output -> System.out.printf("onEvent result:\n" + chainToString(output)))
        .flatMap(output -> switch (finalResult(output)) {
          case Accepted<?> _, Completed<?> _ -> storeChanges(now, correlationId, output)
//              .doOnSuccess(_ -> triggerActions(correlationId, output))
              .thenReturn(output.stepOutput());
          case Rejected<?> r -> tuple.t2().rejectModel() == null ? Mono.error(r.exception()) :
              tuple.t2().rejectModel().calculate(
                  new InitialChangeContext<>(
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
// Don't handle here as there's a retry mechanism upstream
//        .onErrorResume(
//            EventAlreadyExists.class,
//            e -> Mono.just(new Raced<>()).doOnNext(_ -> System.out.println("Event raced: " + e.getMessage()))
//        )
        .onErrorResume(
            SecondaryIdAlreadyExists.class,
            e -> eventsByLookupId.execute(e.change().entityModel(), e.secondaryId())
                .map(originalLog -> IdentityResult.rejected(e.secondaryId(), originalLog))
                .flatMap(identityResult -> onEvent(
                    correlationId,
                    eventType,
                    input,
                    eventLog,
                    stage1,
                    List.of(identityResult)
                ))
        );
  }

  private String chainToString(ChangeContext<?> tail) {
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
    return calculateTriggeredEvent(p.stepOutput().trigger(), previous, p.stepOutput().inputData(), true);
  }


  public Mono<State> onStatus(EntitySelector entitySelector, EntityModel entityModel) {
    return eventLog(entitySelector, entityModel, null)
        .map(log -> traversers.get(entityModel).currentState(log));
  }

  private <T, I, O> Mono<EventLog> eventLog(
      EventTrigger<T, I, O> eventTrigger,
      T inputData,
      ChangeContext<?> changeContext
  ) {
    System.out.println("Finding log for " + eventTrigger.eventSpec().eventType().name() + " on " + eventTrigger.entityModel().name());
    return eventTrigger.createEntity() ?
        Mono.just(emptyEventLog(eventTrigger.entityModel())) :
        eventLog(eventTrigger.entitySelectors().getFirst().apply(inputData), eventTrigger.entityModel(), changeContext);
  }

  private <I> Mono<EventLog> eventLog(
      EntitySelector entitySelector,
      EntityModel entityModel,
      @Nullable ChangeContext<?> changeContext
  ) {
    System.out.println("Finding event log for " + entityModel.name() + " with selector " + entitySelector);
    return switch (entitySelector) {
      case EntitySelector.ByIdFromSession _ -> {
        EventLog log = logFromChangeContext(changeContext, entityModel);
        EntityId id = log.entityId();
        log = logFromNestedChanges(id, changeContext);
        if (log == null) yield eventLogByEntityId(entityModel, id);
        else yield Mono.just(log);
      }
      case ById s -> {
        EntityId entityId = s.id();
        yield switch (s.creationMode()) {
          case NeverCreate -> Mono.justOrEmpty(logFromNestedChanges(entityId, changeContext))
              .switchIfEmpty(eventsByEntityId.execute(entityModel, entityId));
          case CreateIfNotExists -> Mono.justOrEmpty(logFromNestedChanges(entityId, changeContext))
              .switchIfEmpty(eventsByEntityId.execute(entityModel, entityId))
              .onErrorResume(UnknownEntity.class, _ -> Mono.just(emptyEventLog(entityModel, entityId)));
          case AlwaysCreate -> Mono.just(emptyEventLog(entityModel, s.id()));
        };
      }
      case BySecondaryId<?> selector -> switch (selector.creationMode()) {
        case NeverCreate -> eventsByLookupId.execute(entityModel, secondaryId(selector))
            .onErrorResume(
                UnknownEntity.class,
                e -> selector.fallback() != null ? eventLog(selector.fallback(), entityModel, changeContext)
                    : Mono.error(e)
            );
        case CreateIfNotExists -> eventsByLookupId.execute(entityModel, secondaryId(selector))
            .onErrorResume(UnknownEntity.class, _ -> Mono.just(emptyEventLog(entityModel)));
        case AlwaysCreate -> Mono.just(emptyEventLog(entityModel));
      };
      case ByLastInIdGroup<?> s -> switch (s.creationMode()) {
        case AlwaysCreate -> throw new IllegalStateException("Unexpected value: " + s.creationMode());
        case CreateIfNotExists ->
            eventsByLastEntity.execute(entityModel, s.model(), s.group(), s.lastPosition())
                .onErrorResume(EntityGroupNotInitialised.class, _ -> Mono.just(emptyEventLog(entityModel)));
        case NeverCreate ->
            eventsByLastEntity.execute(entityModel, s.model(), s.group(), s.lastPosition());
      };
      case EntitySelector s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private <T> SecondaryId<T> secondaryId(BySecondaryId<T> selector) {
    return new SecondaryId<>(selector.model(), selector.value());
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

  private EventLog logFromNestedChanges(EntityId entityId, ChangeContext<?> changeContext) {
    System.out.println("Finding log from change context for entity id " + entityId.value() + ":\n" + chainToString(changeContext));
    for (var c = changeContext; c != null; c = c.previous()) {
      if (c instanceof OutputChangeContext<?>(TransitionModelBuilder.ChangeContext<?> previous, ProcessResult<?> stepOutput)
          && stepOutput instanceof Accepted<?>(Event<?> event, _, _)
          && event.entityId().equals(entityId.value())
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
      boolean skipCircularCheck
  ) {
    I adaptedData = eventTrigger.eventSpec().inputAdapter() != null ? eventTrigger.eventSpec().inputAdapter().apply(data) : null;
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
                  eventTrigger.entityModel(),
                  tuple.t1().entityId(),
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

    public RejectedEvent(EntityModel entityModel, EntityId entityId, String message) {
      super(prefixMessage(null, entityModel, entityId) + ": " + message);
      this.eventType = null;
      this.entityModel = entityModel;
      this.entityId = entityId;
      this.currentState = null;
    }

    public RejectedEvent(EventType<?, ?> eventType, EntityModel entityModel, EntityId entityId, String message) {
      super(prefixMessage(eventType, entityModel, entityId) + ": " + message);
      this.eventType = eventType;
      this.entityModel = entityModel;
      this.entityId = entityId;
      this.currentState = null;
    }

    public RejectedEvent(EventType<?, ?> eventType, EntityModel entityModel, EntityId entityId, State currentState) {
      super(String.format(
          "%s for state [%s]",
          prefixMessage(eventType, entityModel, entityId),
          currentState != null ? currentState.name() : "<no current state>"
      ));
      this.eventType = eventType;
      this.entityModel = entityModel;
      this.entityId = entityId;
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

    public State currentState() {
      return currentState;
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
                    change.entityId().value(),
                    change.newSecondaryIds().stream().map(id -> id.model().name() + ":" + id.data()).toList()
                ),
                change.timeout(),
                change.newEvent() != null ? new Listener.Change.Event(
                    change.newEvent().eventNumber(),
                    change.newEvent().type().name(),
                    change.newEvent().data()
                ) : null,
                change.newSecondaryIds().stream().map(id -> id.model().name() + ":" + id.data()).toList()
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
                                } else {
                                  System.out.println("No response sink for " + changeList.stream().map(c -> c.entityModel().name() + "/" + c.entityId().value() + "/" + c.newEvent().typeName()).toList());
                                }
                              });
                          return ctx;
                        }
                    ).thenReturn(x)
                )
                .doOnNext(_ -> {
                  if (!changes.isEmpty()) {
                    listener.changeAccepted(correlationId, toListenerFormat(changes));
                  }
                })
                // Forward outgoing requests (for guaranteed delivery this will be the first attempt)
// NEW
                .transformDeferredContextual((publisher, ctx) -> publisher
                    .doOnNext(_ -> actionTriggers(changeContext).forEach(actionTrigger ->
                        triggerAction(actionTrigger, correlationId, changeContext)
                            .contextWrite(ctx).subscribe()))
                )
// OLD
//                .transformDeferredContextual((publisher, ctx) -> publisher
//                    .doOnNext(outboxElementsToForward -> outboxElementsToForward.forEach(q ->
//                            forwardInitial(
//                                changeContext,
//                                changes.get(q.changeIndex()),
//                                q.elementId(),
//                                correlationId
//                            ).contextWrite(ctx).subscribe()
//                        )
//                    )
//                )
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
        );
  }

//  private Mono<ForwardStatus> forwardInitial(
//      ChangeContext<?> changeContext,
//      Change change,
//      byte[] queueElementId,
//      String correlationId
//  ) {
//    HttpOutbox.EntityModel queue = (HttpOutbox.EntityModel) change.entityModel();
//    var queueElement = new OutboxElement(
//        queueElementId,
//        // TODO: Use original event log!?
//        new EventLog(change.entityModel(), change.entityId(), List.of(), List.of(change.newEvent())),
//        correlationId,
//        1,
//        null,
//        null
//    );
//    return doForward(changeContext, queueElement, queue.maxRetryAttempts(), queue.retryInterval());
//  }
//
//  Mono<ForwardStatus> forward(OutboxElement queueElement) {
//    return doForward(null, queueElement, 0, null).contextWrite(Correlation.contextOf(queueElement.correlationId()));
//  }

//  private record ResponseValidationResult(Result validationResult, IncomingResponse response) {}

//  private Mono<ForwardStatus> doForward(
//      @Nullable ChangeContext<?> requestChangeContext,
//      OutboxElement queueElement,
//      int maxRetryAttempts,
//      Duration retryInterval
//  ) {
//    HttpOutbox.EntityModel outboxModel = (HttpOutbox.EntityModel) queueElement.requestLog().entityModel();
//    Mono<HttpRequestMessage> rm = queueElement.attempt() > 1 ?
//        outboxModel.repeatedReactive(queueElement.requestLog().last(HttpRequestMessage.class)) :
//        Mono.just(queueElement.requestLog().last(HttpRequestMessage.class));
//    return rm.flatMap(request -> {
//      return clients.apply(outboxModel).exchange(request)
//          .flatMap(responseMessage -> {
////                var responseMessageOnQueue = responseMessageOnQueue(queueElement, responseMessage);
//            return onEvent(
//                queueElement.correlationId(),
//                HttpOutbox.Response,
//                responseMessage,
//                queueElement.requestLog(),
//                requestChangeContext,
//                List.of()
//            );
//            // When ProcessResult.Completed (rename to AcceptedChoice?), check the chosen event (TODO):
//            //    AcceptResponse:
//            //      dequeue
//            //    SoftRejectResponse:
//            //      Request forwarding may be retried according to RetrySpec
//            //      dlq if exhausted, otherwise backoff
//            //    RejectResponse:
//            //      dlq
//            // When ProcessResult.Rejected
//            //    dlq
//            // When onError EventAlreadyExists
//            //    onEvent might be retried (three times, with jitter) to handle races
//            //
//            // Consider:
//            //   Add to TransitionModel instructions to
//            //     * dequeue (remove queue entry)
//            //     * die (add dlq entry)
//            //     * backoff (update queue entry)
//            //   If so the above handling might only care about retrying request forwarding and/or event triggering.
//          })
//          .flatMap(processResult -> processResult.isRejected() ?
//              Mono.error(new TransientError(processResult)) :
//              Mono.just(processResult)
//          )
////              .flatMap(validationOutput -> validationOutput.validationResult().status() == Status.TransientError ?
////                  Mono.error(new TransientError(validationOutput)) :
////                  Mono.just(validationOutput)
////              )
//          .retryWhen(RetrySpec.fixedDelay(maxRetryAttempts, retryInterval)
//              .filter(e -> e instanceof TransientError && maxRetryAttempts > 0))
//          .thenReturn(ForwardStatus.Ok);
//    });
//  }
//              .onErrorResume(TransientError.class, e -> Mono.just(e.transientResult))
//              .flatMap(validationOutput -> switch (validationOutput) {
//                case ResponseValidationResult r when r.validationResult().inputEvent() == null ->
//                    Mono.just(new IncomingResponseStatus(
//                            r.response(),
//                            ProcessResult.rejected(
//                                null,
//                                new RejectedEvent(
//                                    queueElement.entityModel(),
//                                    eventLog.entityId(),
//                                    "Asynchronous response has no event"
//                                )
//                            ),
//                            r.validationResult()
//                        )
//                    );
//                case ResponseValidationResult r when eventLog.lastEventNumber() > queueElement.eventNumber() ->
//                    Mono.just(new IncomingResponseStatus(
//                            r.response(),
//                            ProcessResult.rejected(
//                                null,
//                                new RejectedEvent(
//                                    queueElement.entityModel(),
//                                    eventLog.entityId(),
//                                    "Response event was raced by (an)other event(s) (" +
//                                        eventLog.subLog(queueElement.eventNumber() + 1).stream()
//                                            .map(Event::typeName)
//                                            .collect(joining(","))
//                                )
//                            ),
//                            r.validationResult()
//                        )
//                    );
//                case ResponseValidationResult r -> onEvent(queueElement.correlationId(), r.validationResult().inputEvent(), eventLog, r.response, requestChangeContext)
//                    .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(500))
//                        .filter(e -> e instanceof EventAlreadyExists)
//                        // Avoid the "Thundering Herd" problem
//                        .jitter(1.0)
//                        .doAfterRetry(signal -> System.out.println(
//                            System.currentTimeMillis() + ": Retried (" + signal.totalRetries() + ") due to "
//                                + signal.failure().getMessage()))
//                        // Rethrow the exception on exhaustion so it can be handled downstream
//                        .onRetryExhaustedThrow((_, signal) -> signal.failure())
//                    )
//                    .map(processResult -> new IncomingResponseStatus(r.response(), processResult, r.validationResult()));
//              })
//              .flatMap(result -> switch (result.processResult()) {
//                    case Accepted<?> _, Completed<?> _ -> Mono.just(ForwardStatus.Ok);
//                    case Rejected<?> r -> switch (result.validationResult().status()) {
//                      case Ok -> dequeueAndStoreReceipt.execute(
//                              queueElement,
//                              result.response().message(),
//                              ZonedDateTime.now(clock)
//                          )
//                          .thenReturn(ForwardStatus.Ok)
//                          .doOnSuccess(_ -> logForwarded(
//                              queueElement,
//                              result.response().message(),
//                              "Forwarded and dequeued, as response event " +
//                                  (result.validationResult().inputEvent() != null ?
//                                      result.validationResult().inputEvent().eventType().name() :
//                                      "N/A"
//                                  ) + " was rejected: " + r.exception.getMessage()
//                          ));
//                      case PermanentError -> moveToDLQ.execute(queueElement, result.validationResult().message())
//                          .doOnSuccess(_ -> logDead(queueElement, result.validationResult().message()))
//                          .thenReturn(ForwardStatus.Ok);
//                      case TransientError -> backOffOrDie(
//                          queueElement,
//                          requireNonNullElse(result.validationResult().message(), "TransientError")
//                      ).thenReturn(ForwardStatus.Ok);
//                    };
//                    //case Raced<?> _ -> Mono.error(new IllegalStateException("Raced response not handled"));
//                    //case Failed<?> r -> backOffOrDie(queueElement, r.reason()).thenReturn(ForwardStatus.Ok);
//                    case ProcessResult<?> r -> Mono.error(new IllegalStateException("Unexpected value: " + r));
//                  }
//              );
//        })
//        .onErrorResume(e -> backOffOrDie(queueElement, e));
//  }

//  private OutgoingRequestModel<?, ?> findOutgoingRequestModel(
//      EventLog eventLog,
//      int eventNumber,
//      OutboxQueue queue,
//      UUID requestCreatorId
//  ) {
//    TransitionModel<?, ?> transitionForEvent = traversers.get(eventLog.entityModel())
//        .transitionForEventNumber(eventLog, eventNumber);
//    return unnest(transitionForEvent).stream().flatMap(t -> t.outgoingRequests().stream())
//        .filter(model -> {
//              if (!model.queue().equals(queue))
//                return false;
//              OutgoingRequestCreator<?> c = model.creator();
//              return c.id().equals(requestCreatorId);
//            }
//        )
//        .findFirst()
//        .orElseThrow(() -> new RuntimeException(String.format(
//            """
//            No outgoing request model found for %s:%s with event number %d on queue %s.
//            Available:
//            %s
//            """,
//            eventLog.entityModel().name(),
//            eventLog.entityId().value(),
//            eventNumber,
//            queue.name(),
//            eventLog.events()
//                .stream()
//                .flatMap(e -> traversers.get(eventLog.entityModel())
//                    .transitionForEventNumber(eventLog, e.eventNumber())
//                    .outgoingRequests()
//                    .stream()
//                    .map(m -> String.format(
//                        "#%d[%s] q[%s] id[%s]",
//                        e.eventNumber(),
//                        e.typeName(),
//                        m.queue().name(),
//                        m.creator().id()
//                    ))
//                )
//                .collect(joining("\n"))
//        )));
//  }

  private static class TransientError extends RuntimeException {

    ProcessResult<?> transientResult;

    public TransientError(ProcessResult<?> transientResult) {
      this.transientResult = transientResult;
    }
  }

  // TODO
  private final DelaySpecification backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);

  private Mono<ForwardStatus> backOffOrDie(OutboxElement queueElement, Throwable e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return backOffOrDie(queueElement, sw.toString());
  }

  private Mono<ForwardStatus> backOffOrDie(OutboxElement queueElement, String reason) {
    if (queueElement.nextAttemptAt() != null && backoff.isExhausted(
        queueElement.requestLog().created(),
        queueElement.nextAttemptAt(),
        clock
    )) {
      return moveToDLQ.execute(queueElement, reason)
          .doOnSuccess(_ -> logDeadByExhaustion(queueElement, reason))
          .thenReturn(ForwardStatus.Dead);
    } else {
      logBackoff(queueElement, reason);
      return Mono.just(ForwardStatus.Backoff);
    }
  }

  private void logDeadByExhaustion(OutboxElement e, String reason) {
//    listener.forwardingDeadByExhaustion(
//        e.requestId(),
//        e.entityModel(),
//        e.queue().name(),
//        e.entityId(),
//        e.eventNumber(),
//        e.enqueuedAt(),
//        e.attempt(),
//        e.correlationId(),
//        reason
//    );
  }

  private void logForwarded(OutboxElement e, HttpResponseMessage responseMessage, String reason) {
//    listener.forwardingCompleted(
//        e.requestId(),
//        e.entityModel(),
//        e.queue().name(),
//        e.entityId(),
//        e.eventNumber(),
//        e.enqueuedAt(),
//        e.attempt(),
//        e.correlationId(),
//        responseMessage,
//        reason
//    );
  }

  private void logBackoff(OutboxElement e, String reason) {
    // TODO: e.backoff() requires processedAt nextAttemptAt
//    listener.forwardingBackedOff(
//        e.requestId(),
//        e.entityModel(),
//        e.queue().name(),
//        e.entityId(),
//        e.eventNumber(),
//        e.enqueuedAt(),
//        e.attempt(),
//        e.correlationId(),
//        reason,
//        e.nextAttemptAt(),
//        e.backoff()
//    );
  }

  private void logDead(OutboxElement e, String reason) {
//    listener.forwardingDead(
//        e.requestId(),
//        e.entityModel(),
//        e.queue().name(),
//        e.entityId(),
//        e.eventNumber(),
//        e.enqueuedAt(),
//        e.attempt(),
//        e.correlationId(),
//        reason
//    );
  }

  private EntityId parentEntityId(ChangeContext<?> previous, EntityModel parentEntityModel) {
    EntityId parentEntityId = null;
    for (var c = previous; c != null; c = c.previous()) {
      if (c instanceof ChangeContext.OutputChangeContext<?> o) {
        if (o.stepOutput().accepted().entityModel().equals(parentEntityModel)) {
          parentEntityId = new EntityId.UUID(o.stepOutput().accepted().event().entityId());
          break;
        }
      }
    }
    return parentEntityId;
  }

  private EntityId newEntityId() {
    return new EntityId.UUID(UUID.randomUUID());
  }

  public Traverser traverser(EntityModel entityModel) {
    return traversers.get(entityModel);
  }

}
