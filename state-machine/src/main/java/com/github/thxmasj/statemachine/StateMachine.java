package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Correlation.correlationId;
import static com.github.thxmasj.statemachine.OutgoingRequestCreator.context;
import static com.github.thxmasj.statemachine.OutgoingRequestCreator.reversalContext;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Collections.unmodifiableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.EntitySelector.BySecondaryId;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Context;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result.Status;
import com.github.thxmasj.statemachine.OutboxWorker.ForwardStatus;
import com.github.thxmasj.statemachine.State.Timeout;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Accepted;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Completed;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.DuplicateId;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Failed;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Pending;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Raced;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Rejected;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.UnknownId;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.ChoiceChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.CombinedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.IdentityChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.PendingChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.ChangeContext.UnknownIdChangeContext;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.EntityGroupNotInitialised;
import com.github.thxmasj.statemachine.database.EventAlreadyExists;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.SecondaryIdAlreadyExists;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCClient;
import com.github.thxmasj.statemachine.database.mssql.ChangeState;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.CreateSchema;
import com.github.thxmasj.statemachine.database.mssql.DequeueAndStoreReceipt;
import com.github.thxmasj.statemachine.database.mssql.EventsByEntityId;
import com.github.thxmasj.statemachine.database.mssql.EventsByLastEntity;
import com.github.thxmasj.statemachine.database.mssql.EventsByLookupId;
import com.github.thxmasj.statemachine.database.mssql.LastSecondaryId;
import com.github.thxmasj.statemachine.database.mssql.Mappers;
import com.github.thxmasj.statemachine.database.mssql.MoveToDLQ;
import com.github.thxmasj.statemachine.database.mssql.NextDeadline;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.RequestMapper;
import com.github.thxmasj.statemachine.message.Message;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
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
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;
import reactor.util.context.ContextView;
import reactor.util.retry.RetrySpec;

public class StateMachine {

  private final Function<List<String>, Mono<Void>> delayer;
  private final BeanRegistry beanRegistry;
  private final Clock clock;
  private final Listener listener;
  @SuppressWarnings("ALL")
  private final List<Looper<?>> workers = new ArrayList<>();
  private final ChangeState changeState;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByLookupId eventsByLookupId;
  //private final EventsByMessageId eventsByMessageId;
  private final EventsByLastEntity eventsByLastEntity;
  //private final SecondaryIdByEntityId secondaryIdByEntityId;
  private final LastSecondaryId lastSecondaryId;
  private final DequeueAndStoreReceipt dequeueAndStoreReceipt;
  private final MoveToDLQ moveToDLQ;
  private final NextDeadline nextDeadline;
  //private final OutgoingResponseAndRequestDigestByRequest outgoingResponseByRequest;
  private final OutgoingRequestByEvent outgoingRequestByEvent;
  private final Map<UUID, OutgoingRequestCreator<?>> outgoingRequestCreators;
  private final Function<OutboxQueue, HttpClient> clients;
  //private final Map<EntityModel, TraversableState> begin;
  private final Map<EntityModel, Traverser> traversers;

  public StateMachine(
      RequestMapper requestMapper,
      Function<List<String>, Mono<Void>> delayer,
      BeanRegistry beanRegistry,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener,
      Function<OutboxQueue, HttpClient> clients
  ) {
    //this.begin = transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> TraversableState.create(e.getKey(), e.getValue())));
    this.traversers = transitions.entrySet().stream().collect(toMap(Entry::getKey, e -> new Traverser(e.getValue())));
    List<EntityModel> entityModels = transitions.keySet().stream().toList();
    List<TransitionModel<?, ?>> allTransitions = transitions.values()
        .stream()
        .flatMap(m -> m.values().stream())
        .flatMap(Collection::stream)
        .toList();
//    List<EventType<?, ?>> allEventTypes = Stream.concat(
//        allTransitions.stream().flatMap(t -> Mappers.eventTypesFor(t).stream()),
//        BuiltinEventTypes.ALL.stream()
//    ).distinct().toList();
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
    this.outgoingRequestCreators = Stream.concat(
            allTransitions.stream().flatMap(t -> t.outgoingRequests().stream()),
            allTransitions.stream()
                .filter(t -> t.reverseModel() != null)
                .map(TransitionModel::reverseModel)
                .flatMap(t -> t.outgoingRequests().stream())
        )
//            entityModels.stream()
//                    .flatMap(e -> e.transitions().values().stream().flatMap(Collection::stream))
//                    .flatMap(t -> t.outgoingRequests().stream()),
//                entityModels.stream()
//                    .flatMap(e -> e.transitions().values().stream().flatMap(Collection::stream))
//                    .filter(t -> t.reverseModel() != null)
//                    .map(TransitionModel::reverseModel)
//                    .flatMap(t -> t.outgoingRequests().stream())
//            )
        .filter(r -> r.creator() != null)
        .map(OutgoingRequestModel::creator)
        .distinct()
        .collect(toMap(OutgoingRequestCreator::id, nc -> nc));
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    this.beanRegistry = beanRegistry;
    if (schemaDataSource != null) {
      new CreateSchema(entityModels, schemaName, role).execute(new JDBCClient(schemaDataSource))
          .blockOptional(ofSeconds(10));
    }
    this.clock = clock;
    var jdbcClient = new JDBCClient(dataSource);
    this.changeState = new ChangeState(entityModels, jdbcClient, schemaName, clock);
    var eventMappers = eventTypes.entrySet().stream().collect(toMap(Entry::getKey, e -> Mappers.eventMapper(e.getValue(), clock)));
    this.eventsByEntityId = new EventsByEntityId(dataSource, entityModels, schemaName, eventMappers);
    this.eventsByLookupId = new EventsByLookupId(dataSource, entityModels, schemaName, eventMappers);
    //this.eventsByMessageId = new EventsByMessageId(dataSource, entityModels, schemaName, eventMapper);
    this.eventsByLastEntity = new EventsByLastEntity(
        dataSource,
        entityModels,
        schemaName,
        eventTypes.entrySet().stream().collect(toMap(Entry::getKey, e -> Mappers.eventTypeMapper(e.getValue()))),
        clock
    );
    //this.secondaryIdByEntityId = new SecondaryIdByEntityId(dataSource, entityModels, schemaName);
    this.lastSecondaryId = new LastSecondaryId(dataSource, entityModels, schemaName);
    this.dequeueAndStoreReceipt = new DequeueAndStoreReceipt(jdbcClient, schemaName, clock);
    this.moveToDLQ = new MoveToDLQ(jdbcClient, schemaName);
    this.nextDeadline = new NextDeadline(jdbcClient, clock, entityModels, schemaName);
    //this.outgoingResponseByRequest = new OutgoingResponseAndRequestDigestByRequest(dataSource, schemaName);
    this.outgoingRequestByEvent = new OutgoingRequestByEvent(dataSource, schemaName);
    this.listener = listener;
    this.clients = clients;
    // TODO: Differentiate delay spec per queue
    var backoff = new DelaySpecification(ofSeconds(10), ofSeconds(20), ofSeconds(100), 1.5);
    var processBackedOff = new ProcessBackedOff(jdbcClient, entityModels, schemaName, clock, backoff);
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
    for (var entityModel : entityModels) {
      for (var queue : entityModel.queues()) {
        var looper = new OutboxWorker(
            this,
            processBackedOff,
            listener,
            queue,
            clock
        ).forwarder(true);
        looper.loop();
        workers.add(looper);
      }
    }
  }

  private EventLog emptyEventLog(EntityModel entityModel) {
    return new EventLog(entityModel, newEntityId(), List.of(), List.of());
  }

  private EventLog emptyEventLog(EntityModel entityModel, EntityId entityId) {
    return new EventLog(entityModel, entityId, List.of(), List.of());
  }

  /*
    public Mono<ProcessResult> processRequest(String requestData) {
      try {
        HttpRequestMessage message;
        try {
          message = HttpMessageParser.parseRequest(requestData);
        } catch (Exception e) {
          return Mono.just(new ProcessResult(Status.Rejected, null, String.format("Failed to parse incoming message: %s", e.getMessage())));
        }
        System.out.println("Process request " + message.requestLine());
        IncomingRequestModelBuilder requestModelBuilder;
        try {
          requestModelBuilder = requestMapper.incomingRequest(message);
        } catch (Exception e) {
          return Mono.just(new ProcessResult(Status.Rejected, null, String.format("Failed to map incoming request: %s\n%s", e, message.message())));
        }
        if (requestModelBuilder == null) {
          return Mono.just(new ProcessResult(Status.Rejected, null, String.format("No mapping for incoming message: %s", message.requestLine())));
        }
        IncomingRequestModel<?> requestModel;
        try {
          requestModel = requestModelBuilder.build();
        } catch (Exception e) {
          return Mono.just(new ProcessResult(Status.Rejected, null, String.format("Failed to build request model for incoming message: %s\n%s", e, message.requestLine())));
        }
        var incomingRequest = new IncomingRequest(
            UUID.randomUUID(),
            requestModel.clientId(),
            requestModel.derivedMessageId() ? (entityId, eventType) -> entityId.value() + "-" + eventType.id() : (_, _) -> requestModel.messageId(),
            requestModel.digest() != null ? requestModel.digest() : MessageDigest.getInstance("SHA256").digest(message.message().getBytes(StandardCharsets.UTF_8)),
            message
        );
        EventTrigger<?, ?, ?> eventTrigger = requestModel.eventTrigger(); // TODO: Errors end up in the big try/catch. Related: https://github.com/orgs/the-backend-project/projects/1/views/1?pane=issue&itemId=79673689
        String correlationId = requireNonNullElse(requestModel.correlationId(), requireNonNullElse(message.headerValue("X-Correlation-Id"), "processRequest"));
        One<HttpResponseMessage> responseSink = Sinks.one();
        return (eventTrigger.eventSpec().eventType() instanceof BasicEventType.Rollback ?
            doProcessRollback(1, incomingRequest, requestModel) :
            doProcessIncomingRequest(1, incomingRequest, requestModel)
        )
            .flatMap(processResult -> processResult.responseMessage() != null ?
                Mono.just(processResult) :
                responseSink.asMono()
                    .map(responseMessage -> new ProcessResult(processResult.status(), responseMessage, processResult.error()))
                    .timeout(ofSeconds(20))
                )
            .contextWrite(Correlation.contextOf(correlationId, responseSink, incomingRequest.id()));
      } catch (Exception e) {
        listener.clientRequestFailed("N/A", null, e);
        return Mono.just(new ProcessResult(Failed, null, "Request failed: " + e));
      }
    }
  */
  public enum ResolverStatus {Ok, Empty, Error}

  /**
   * Resolve a state that has reached its deadline, as indicated by its timeout value.
   */
  public Mono<ResolverStatus> resolveState() {
    var backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);
    return nextDeadline.execute(backoff)
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
          //var currentState = begin(deadline.entityModel()).forward(eventLog.events().stream().map(Event::type).toList());
          var currentState = traversers.get(deadline.entityModel()).currentState(eventLog);
          if (currentState == null)
            return Mono.error(new RuntimeException(
                "Invalid event log: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          InputEvent<?> event = currentState.timeout()
              .map(Timeout::event)
              .orElseThrow(() -> new RuntimeException(
                  "Huh? Resolving a state without timeout?? State is " + currentState.name() + " with transitions "
                      + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          return onEvent(
              deadline.correlationId(),
              event,
              eventLog,
              null,
              null
          )
//          return processEvents(
//              eventLog,
//              event,
//              null,
//              deadline.correlationId()
//          )
              .flatMap(processResult -> switch (processResult) {
                    // State is resolved and deadline already deleted by the change triggered by this event.
                    case Accepted _ -> Mono.just(ResolverStatus.Ok);
                    // State is resolved and deadline already deleted by the change triggered by the racing event.
                    case Raced _ -> Mono.just(ResolverStatus.Ok);
                    // Need to retry. Deadline was already modified when reading.
                    case Failed r -> {
                      listener.resolveStateFailed(
                          deadline.correlationId(),
                          eventLog.entityId(),
                          currentState.name(),
                          event.eventType(),
                          r.reason()
                      );
                      yield backoff.isExhausted(eventLog.events().getLast().timestamp(), deadline.nextAttemptAt(), clock) ?
                          Mono.error(new RuntimeException("Period for state resolving exhausted: " + Duration.between(
                              eventLog.events()
                              .getLast()
                              .timestamp(), ZonedDateTime.now(clock)
                          ))) :
                          Mono.just(ResolverStatus.Ok);
                    }
                    // This is a bug.
                    // - Rejection should not happen unless model is wrong. TODO: sanitize
                    // - Repeated and DuplicateId should only happen with incoming requests (which this is not).
                    case ProcessResult r -> Mono.error(new IllegalStateException(
                        "Unexpected result for state resolving: " + r.getClass().getSimpleName()));
                  }
              ).contextWrite(Correlation.contextOf(deadline.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(listener::processNextDeadlineFailed)
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

//  private Mono<ProcessResult> invalidRequest(
//      Message.IncomingRequest incomingRequest,
//      EventLog eventLog,
//      String reason
//  ) {
//    return processEventForInvalidOrRejectedRequest(
//        BuiltinEventTypes.InvalidRequest,
//        incomingRequest,
//        eventLog,
//        reason
//    );
//  }

//  private Mono<ProcessResult> rejectedRequest(
//      String messageId,
//      IncomingRequest incomingRequest,
//      EventLog eventLog,
//      String reason
//  ) {
//    return processEventForInvalidOrRejectedRequest(
//        BuiltinEventTypes.RejectedRequest,
//        incomingRequest(eventLog.lastEventNumber() + 1, incomingRequest, messageId),
//        eventLog,
//        reason
//    );
//  }

//  private Mono<ProcessResult> processEventForInvalidOrRejectedRequest(
//      EventType<String, Void> eventType,
//      Message.IncomingRequest incomingRequest,
//      EventLog eventLog,
//      String reason
//  ) {
//    return processEvents(
//        eventLog,
//        List.of(),
//        new InputEvent<>(eventType, reason),
//        incomingRequest,
//        List.of()
//    );
//  }

//  private Mono<ProcessResult> repeatedRequest(EntityId entityId, EntityModel entityModel, String clientId, String messageId, HttpResponseMessage responseMessage) {
//    return correlationId()
//        .doOnNext(correlationId -> listener.repeatedRequest(correlationId, entityId, clientId, messageId))
//        .map(_ -> new ProcessResult(Status.Repeated, responseMessage, null));
//  }

//  private Mono<EventLog> fetchEventLogByEntitySelector(
//      HttpRequestMessage requestMessage,
//      String clientId,
//      EntityModel entityModel,
//      EntitySelector<HttpRequestMessage> entitySelector
//  ) {
//    return switch (entitySelector) {
//      case EntitySelector.ById<HttpRequestMessage> s -> eventsByEntityId.execute(entityModel, s.id().apply(requestMessage));
//      case EntitySelector.BySecondaryId<HttpRequestMessage> s ->
//          eventsByLookupId.execute(entityModel, new SecondaryId(s.model(), s.id().apply(requestMessage)));
//      case EntitySelector.ByMessageId<HttpRequestMessage> s -> eventsByMessageId.execute(entityModel, s.messageId().apply(requestMessage), clientId);
//      case EntitySelector<?> s -> throw new IllegalStateException("Unexpected value: " + s);
//    };
//  }

/*
  private <T, I, O> Mono<ProcessResult> doProcessIncomingRequest(
      int attempt,
      IncomingRequest incomingRequest,
      IncomingRequestModel<T, I, O> requestModel
  ) {
    EventTrigger<T, I, O> eventTrigger = requestModel.eventTrigger();
    IncomingRequestValidator<T> validator = requestModel.validatorClass() != null ? beanRegistry.getBean(requestModel.validatorClass()) : requestModel.validator();
    return (eventTrigger.createEntity() ? Mono.just(emptyEventLog(eventTrigger.entityModel())) :
        fetchEventLogByEntitySelector(
            incomingRequest.requestMessage(),
            incomingRequest.clientId(),
            eventTrigger.entityModel(),
            eventTrigger.entitySelectors().getFirst()
        )
            .onErrorResume(
                UnknownEntity.class,
                e -> {
                  System.out.println("Unknown entity: " + e);
                  var mainEntitySelector = eventTrigger.entitySelectors().getFirst();
                  if (mainEntitySelector instanceof EntitySelector.BySecondaryId<T> s &&
                      isInitialInGroup(new SecondaryId(s.model(), s.id().apply(incomingRequest.requestMessage())))) {
                    // Will trigger creation of secondary id
                    return Mono.just(emptyEventLog(eventTrigger.entityModel()));
                  } else if (mainEntitySelector instanceof EntitySelector.ById<?> s
                      && s.creationMode() == CreateIfNotExists) {
                    System.out.println("Creating new entity: " + s.id().value());
                    return Mono.just(emptyEventLog(eventTrigger.entityModel(), s.id()));
                  } else {
                    return Mono.error(e);
                  }
                }
            )
    )
        .flatMap(eventLog -> {
          EntityId entityId = eventLog.entityId();
          var currentState = begin(eventTrigger.entityModel()).forward(eventLog.effectiveEvents()
              .stream()
              .map(Event::type)
              .toList());
          List<EventType<Void, ?>> scheduledEvents = scheduledEvents(eventTrigger.entityModel(), eventLog);
          int nextEventNumber = eventLog.lastEventNumber() + scheduledEvents.size() + 1;
          String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventSpec().eventType());
          Message.IncomingRequest requestMessage = incomingRequest(nextEventNumber, incomingRequest, messageId);
          var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream().toList());
          if (stateAfterScheduledEvents == null) {
            // TODO: Handle better. InconsistentState?
            throw new RuntimeException("Can't apply scheduled events " +
                scheduledEvents.stream().map(EventType::name).collect(joining(",")) +
                " to state " + currentState.state());
          }
          // TODO: Need to check that state accepts the event for the request before validation, otherwise
          //       validation can fail with RequirementsNotFulfilled. This duplicates the behavior, though
          //       (see below, rejectIfNotRepeat called twice).
          if (stateAfterScheduledEvents.forward(eventTrigger.eventSpec().eventType()) == null) {
            return rejectIfNotRepeat(
                entityId,
                eventLog,
                messageId,
                incomingRequest,
                "State " + eventTrigger.entityModel().name() + "/" + stateAfterScheduledEvents.state()
                    + " does not accept " + eventTrigger.eventSpec().eventType() + " (" + eventTrigger.eventSpec()
                    .eventType()
                    .id() + ")"
            );
          }
          return validateRequest(
              incomingRequest.clientId(),
              incomingRequest.requestMessage(),
              validator,
              eventTrigger.eventSpec().eventType()
          )
              .flatMap(validationResult -> switch (validationResult) {
                case Valid<?> v -> processEvents(
                    eventLog,
                    scheduledEvents,
                    v.value(),
                    requestMessage,
                    eventLog.events().isEmpty() && !eventTrigger.entitySelectors().isEmpty()
                        && eventTrigger.entitySelectors().getFirst() instanceof EntitySelector.BySecondaryId<T> s ?
                        List.of(new SecondaryId(s.model(), s.id().apply(incomingRequest.requestMessage()))) :
                        List.of()
                );
                case Invalid v -> processEvents(
                    eventLog,
                    scheduledEvents,
                    v.error(),
                    requestMessage,
                    eventLog.events().isEmpty() && !eventTrigger.entitySelectors().isEmpty()
                        && eventTrigger.entitySelectors().getFirst() instanceof EntitySelector.BySecondaryId<T> s ?
                        List.of(new SecondaryId(s.model(), s.id().apply(incomingRequest.requestMessage()))) :
                        List.of()
                );
              })
              .flatMap(result -> result.status() == Status.Rejected ?
                  rejectIfNotRepeat(entityId, eventLog, messageId, incomingRequest, result.error()) :
                  Mono.just(result)
              )
              .filter(processResult -> isUnrepeatable(
                  attempt,
                  processResult.status(),
                  isPendingIncomingResponse(eventLog)
              ))
              .switchIfEmpty(
                  reattemptDelay.then(doProcessIncomingRequest(attempt + 1, incomingRequest, requestModel))
              );
        })
        .onErrorResume(
            UnknownEntity.class, e -> {
              EntityId entityId = e.id() != null ? e.id() : newEntityId();
              String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventSpec().eventType());
              return processEvents(
                  emptyEventLog(eventTrigger.entityModel(), entityId),
                  List.of(),
                  new InputEvent<>(BuiltinEventTypes.UnknownEntity, null),
                  incomingRequest(1, incomingRequest, messageId),
                  List.of()
              );
            }
        )
        .onErrorResume(t -> withCorrelationId(correlationId -> listener.clientRequestFailed(
            correlationId,
            eventTrigger.eventSpec().eventType(),
            t
        )).then(Mono.error(t)));
  }
*/
//  private Message.IncomingRequest incomingRequest(
//      int eventNumber,
//      IncomingRequest incomingRequest,
//      String messageId
//  ) {
//    return new Message.IncomingRequest(
//        incomingRequest.id(),
//        eventNumber,
//        incomingRequest.requestMessage().withoutHeader("Authorization"),
//        messageId,
//        incomingRequest.clientId(),
//        incomingRequest.digest()
//    );
//  }

//  private Mono<ProcessResult> rejectIfNotRepeat(
//      EntityId entityId,
//      EventLog eventLog,
//      String messageId,
//      IncomingRequest incomingRequest,
//      String errorMessage
//  ) {
//    return outgoingResponseByRequest.execute(messageId, incomingRequest.clientId())
//        .flatMap(inboxEntry -> Arrays.equals(inboxEntry.requestDigest(), incomingRequest.digest()) ?
//            repeatedRequest(
//                entityId,
//                eventLog.entityModel(),
//                incomingRequest.clientId(),
//                messageId,
//                inboxEntry.responseMessage()
//            ) :
//            invalidRequest(
//                incomingRequest(eventLog.lastEventNumber() + 1, incomingRequest, messageId),
//                eventLog,
//                "Message identifier not unique"
//            )
//        )
//        // Request is not a repeat, so reject it.
//        .switchIfEmpty(rejectedRequest(
//            'T' + messageId, // don't include temporary rejected incoming requests in repeat check
//            incomingRequest,
//            eventLog,
//            errorMessage
//        ));
//  }

/*
  private <T> Mono<IncomingRequestValidator.Result> validateRequest(
      String clientId,
      HttpRequestMessage requestMessage,
      IncomingRequestValidator<T> validator,
      EventType<T, ?> validEventType
  ) {
    return validator.execute(
        new IncomingRequestContext<>(validEventType),
        clientId,
        requestMessage
    );
  }
*/

  private Mono<Result> validateResponse(
      HttpRequestMessage requestMessage,
      HttpResponseMessage responseMessage,
      IncomingResponseValidator<?> validator,
      EntityId entityId,
      int currentEventNumber
  ) {
    return validator.execute(
        entityId,
        new IncomingResponseContext<>(currentEventNumber),
        requestMessage,
        new Input.IncomingResponse(
            responseMessage,
            currentEventNumber
        )
    );
  }

/*
  private static class IncomingRequestContext<DATA_TYPE> implements Context<DATA_TYPE> {

    private final EventSpec<DATA_TYPE, ?, ?> validRequestEventType;

    private IncomingRequestContext(
        EventSpec<DATA_TYPE, ?, ?> validRequestEventType
    ) {
      this.validRequestEventType = validRequestEventType;
    }

    @Override
    public Invalid invalidRequest(String errorMessage) {
      return new Invalid(new InputEvent<>(BuiltinEventTypes.InvalidRequest, errorMessage));
    }

    @Override
    public Valid<DATA_TYPE> invalidRequest(EventType<DATA_TYPE, ?> eventType, DATA_TYPE data, String errorMessage) {
      return new Valid<>(new InputEvent<>(eventType, data));
    }

    @Override
    public Valid<DATA_TYPE> invalidRequest(EventType<DATA_TYPE, ?> eventType, DATA_TYPE data) {
      return new Valid<>(new InputEvent<>(eventType, data));
    }

    @Override
    public Valid<DATA_TYPE> validRequest(DATA_TYPE data) {
      return new Valid<>(new InputEvent<>(validRequestEventType, data));
    }

    @Override
    public Valid<DATA_TYPE> validRequest() {
      return new Valid<>(new InputEvent<>(validRequestEventType, null));
    }

  }
*/

  private static class IncomingResponseContext<DATA_TYPE> implements Context<DATA_TYPE> {

    private final int currentEventNumber;

    private IncomingResponseContext(int currentEventNumber) {this.currentEventNumber = currentEventNumber;}

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
          Rollback, new Data(
          currentEventNumber - 2, // To the event before the request (current is response)
          cause
      )
      );
    }
  }

  private record IncomingResponseStatus(
      Message.IncomingResponse response,
      ProcessResult<?> processResult,
      Result validationResult
  ) {}

/*
  private <T> Mono<ProcessResult> doProcessRollback(
      int attempt,
      IncomingRequest incomingRequest,
      IncomingRequestModel<T> requestModel
  ) {
    EventTrigger<HttpRequestMessage, T, ?> eventTrigger = requestModel.eventTrigger();
    EntityModel entityModel = eventTrigger.entityModel();
    return fetchEventLogByEntitySelector(
        incomingRequest.requestMessage(),
        incomingRequest.clientId(),
        eventTrigger.entityModel(),
        eventTrigger.entitySelectors().getFirst()
    )
        .onErrorResume(
            UnknownEntity.class,
            e -> {
              var mainEntitySelector = eventTrigger.entitySelectors().getFirst();
              if (mainEntitySelector instanceof EntitySelector.BySecondaryId<HttpRequestMessage> s &&
                  isInitialInGroup(new SecondaryId(s.model(), s.id().apply(incomingRequest.requestMessage())))) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel()));
              } else if (mainEntitySelector instanceof EntitySelector.ById<HttpRequestMessage> s && s.creationMode() == CreateIfNotExists) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel(), s.id()));
              } else if (mainEntitySelector instanceof EntitySelector.ByMessageId<HttpRequestMessage> s && s.creationMode() == CreateIfNotExists) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel(), newEntityId()));
              } else {
                return Mono.error(e);
              }
            }
        )
        .flatMap(eventLog -> {
          int rollbackToEventNumber = eventTrigger.entitySelectors().getFirst() instanceof EntitySelector.ByMessageId<HttpRequestMessage> s ?
              eventLog.effectiveEvents()
                  .stream()
                  .filter(e -> s.messageId().apply(incomingRequest.requestMessage()).equals(e.messageId()))
                  .map(event -> event.eventNumber() - 1)
                  .findFirst()
                  .orElse(0) :
              0;
          var currentState = begin(entityModel)
              .forward(eventLog.effectiveEvents().stream().map(Event::type).toList());
          if (currentState == null)
            throw new RuntimeException(
                "Cannot traverse from " + begin(entityModel).state() + " with " + eventLog.events()
                    .stream()
                    .map(Event::typeName)
                    .collect(joining(", ")));
          int lastEventNumber = eventLog.lastEventNumber();
          // If original request did not arrive we want it to fail on message id uniqueness constraint when it does.
          // If it did arrive, we don't want the rollback request to fail on this constraint, so we prepend with an "R" to
          // make it unique (small risk that a message already used that id, and the rollback would then be rejected).
          String messageId = (rollbackToEventNumber < lastEventNumber ? "R" : "") +
              ofNullable(
                  eventTrigger.entitySelectors().getFirst() instanceof EntitySelector.ByMessageId<HttpRequestMessage> s ?
                      s.messageId().apply(incomingRequest.requestMessage()) : null)
                  .orElse(incomingRequest.messageId().apply(eventLog.entityId(), eventTrigger.eventSpec().eventType()));
          if (rollbackToEventNumber < lastEventNumber && eventLog.events()
              .stream()
              .anyMatch(e -> e.eventNumber() > rollbackToEventNumber + 1 && e.isIncomingRequest())) {
            return rejectedRequest(
                messageId,
                incomingRequest,
                eventLog,
                "Cannot roll back to event number " + rollbackToEventNumber + " as that would roll back more than one request"
            );
          }
          Message.IncomingRequest incomingRequestMessage = incomingRequest(lastEventNumber + 1, incomingRequest, messageId);
          return processEvents(
              eventLog,
              List.of(),
              new InputEvent<>(eventTrigger.eventSpec().eventType(), null),
              incomingRequestMessage,
              List.of()
          )
              .flatMap(result -> result.status() == Status.Rejected ?
                  rejectedRequest(
                      messageId,
                      incomingRequest,
                      eventLog,
                      result.error()
                  ) :
                  // https://github.com/orgs/the-backend-project/projects/1/views/1?pane=issue&itemId=91389249
                  //rejectIfNotRepeat(entityId, eventLog, incomingRequest, result.error()) :
                  Mono.just(result)
              )
              .filter(pr -> isUnrepeatable(attempt, pr.status(), isPendingIncomingResponse(eventLog)))
              .switchIfEmpty(reattemptDelay.then(
                  doProcessRollback(attempt + 1, incomingRequest, requestModel)
              ));
        })
        .onErrorResume(t -> withCorrelationId(correlationId -> listener.rollbackFailed(correlationId, null, t)).then(Mono.error(t)));
  }
*/

//  private int rollbackToEventNumber(EventLog eventLog) {
//    return eventLog.effectiveEvents().reversed()
//            .stream()
//            .filter(e -> e.messageId() != null)
//            .map(event -> event.eventNumber() - 1)
//            .findFirst()
//            .orElse(eventLog.lastEventNumber());
//  }

//  private boolean isUnrepeatable(int attempt, Status processStatus, boolean stateIsPendingChange) {
//    // A rejected event might be repeatable if current state is pending change shortly.
//    // Using 100 reattempts, then, as we delay for 100 ms to cover changes arriving within 10 seconds (which should
//    // cover synchronous outbox exchanges). TODO: Use a backoff algorithm instead in that case
//    var b =
//        !(processStatus == Rejected && attempt < 100 && stateIsPendingChange) &&
//        !(processStatus == Status.Raced && attempt < 3);
//    System.out.println("Status " + processStatus + " unrepeatable " + b);
//    return b;
//  }

  private Mono<Void> withCorrelationId(Consumer<String> consumer) {
    return correlationId().doOnNext(consumer).then();
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

  public sealed interface ProcessResult<T> permits Accepted, Completed, DuplicateId, Failed, Pending, Raced, Rejected,
      UnknownId {

    record Completed<T>() implements ProcessResult<T> {}

    record Accepted<T>(Event<T> event, EntityModel entityModel) implements ProcessResult<T> {
      public Accepted<T> accepted() {return this;}
    }

    record Rejected<T>(EventType<?, T> eventType, RejectedEvent exception) implements ProcessResult<T> {

      public Accepted<T> accepted() {
        System.out.println("accepted() called on ProcessResult.Rejected");
        throw exception;
      }

      public Rejected<T> rejected() {return this;}
    }

    record DuplicateId<T>(SecondaryId<?> id) implements ProcessResult<T> {}

    record UnknownId<T>(EventType<?, T> eventType, UnknownEntity exception) implements ProcessResult<T> {

      public UnknownId<T> unknownId() {return this;}
    }

    record Failed<T>(String reason) implements ProcessResult<T> {}

    record Raced<T>() implements ProcessResult<T> {}

    record Pending<T, I, O>(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) implements ProcessResult<O> {}

    record Entity(
        EntityId id,
        List<SecondaryId<?>> secondaryIds,
        EntityModel model
    ) {}

//    public enum Status {
//      Accepted, // Event is accepted successfully (and stored)
//      Repeated, // Event is a repeat of a previously accepted event.
//      Rejected, // Event is rejected (not allowed for the current state)
//      DuplicateId, // Event conflicts with a previous event (message id was reused)
//      UnknownId,
//      //Conflicted, // Event conflicts with a previous event (message id was reused)
//      Failed, // Event processing failed (temporarily, can try again)
//      Raced // Storing was raced by another event (can try again)
//    }

    static <T> Completed<T> completed() {
      return new Completed<>();
    }

    static <T> Accepted<T> accepted(Event<T> event, EntityModel entityModel) {
      return new Accepted<>(event, entityModel);
    }

    default boolean isAccepted() {
      return this instanceof Accepted;
    }

    default Accepted<T> accepted() {
      throw new IllegalStateException("Result is " + this);
    }

    static <T> Rejected<T> rejected(EventType<?, T> eventType, RejectedEvent exception) {
      return new Rejected<>(eventType, exception);
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

    static <T, I, O> Pending<T, I, O> pending(EventTrigger<T, I, O> trigger, T inputData, CircularChange exception) {
      return new Pending<>(trigger, inputData, exception);
    }

    default boolean isDuplicateId() {
      return this instanceof DuplicateId;
    }

    static <T> DuplicateId<T> duplicateId(SecondaryId<?> id) {
      return new DuplicateId<>(id);
    }

    default boolean isUnknownId() {
      return this instanceof UnknownId;
    }

    default UnknownId<T> unknownId() {
      return (UnknownId<T>) this;
    }

  }

//  private <I> Flux<ChangeResult> calculateNestedChanges(
//      Entity rootEntity,
//      InputEvent<?> inputEvent,
//      EventLog eventLog,
//      List<EventTrigger<?, ?, ?>> eventTriggers
//  ) {
//    return Flux.fromIterable(eventTriggers)
//        .flatMap(eventTrigger -> calculateNestedChange(
//            rootEntity,
//            (InputEvent<I>) inputEvent,
//            eventLog,
//            (EventTrigger<I, ?, ?>) eventTrigger
//        ));
//  }

//  private <I, I1, O1> Mono<ChangeResult> calculateNestedChange(
//      Entity rootEntity,
//      InputEvent<I> inputEvent,
//      EventLog rootEventLog,
//      EventTrigger<I, I1, O1> eventTrigger
//  ) {
//    return calculateNestedChange(
//        rootEntity,
//        eventTrigger.entitySelectors(),
//        eventTrigger.entityModel(),
//        eventTrigger.eventType(),
//        eventTrigger.data().apply(inputEvent, rootEventLog)
//    );
//  }

//  private Mono<SecondaryId> next(Entity entity, SecondaryIdModel idModel) {
//    if (entity.secondaryIds().isEmpty()) {
//      return secondaryIdByEntityId.execute(entity.model(), idModel, entity.id());
//        // TODO: Guess we never come here, as ids are always loaded with event log. Otherwise we should do:
//        //       .map(id -> idModel.group().next(id));
//    } else {
//      SecondaryId secondaryId = entity.secondaryIds().stream()
//          .filter(ids -> ids.model() == idModel)
//          .findFirst()
//          .orElseThrow(() -> new RuntimeException("No secondary id of type " + idModel + " found on entity " + entity));
//      return Mono.just(secondaryId.model().group().next(secondaryId));
//    }
//  }

//  private Mono<EntitySelector.BySecondaryId<?>> next(Entity entity, EntitySelector.ByNextInIdGroup<?> selector) {
//    return next(entity, selector.model())
//        .map(nextId -> new BySecondaryId<>(selector.model(), _ -> nextId, selector.creationMode()));
//  }

  private Mono<EventLog> eventLogByEntityId(EntityModel entityModel, EntityId entityId) {
    return eventsByEntityId.execute(entityModel, entityId);
//    return Mono.deferContextual(ctx ->
//        ctx.hasKey(entityId) ?
//            /* Circular event chain in same transaction */
//            Mono.just(ctx.get(entityId)) :
//            eventsByEntityId.execute(entityModel, entityId));
  }

  private Mono<EventLog> eventLogFromSession(EntityModel entityModel, ContextView ctx) {
    var log = ctx.<EventLog>getOrEmpty(entityModel).orElseThrow(
        () -> new IllegalStateException("No event log found in session for " + entityModel.name() + ". Found: " +
            ctx.stream()
                .filter(e -> e.getKey() instanceof EntityModel)
                .map(e -> ((EntityModel) e.getKey()).name())
                .collect(
                    joining(", "))
        )
    );
    return eventLogByEntityId(entityModel, log.entityId());
//    System.out.println("Got event log for entity " + entityModel.name() + "/" + log.entityId().value() + " from session: " + log.events().stream().map(Event::typeName).collect(joining(", ")));
//    return log;
  }

//  private <T> Mono<EventLog> fetchEventLogForNestedChange(
//      Entity rootEntity,
//      EntityModel entityModel,
//      EntitySelector<T> mainSelector,
//      List<? extends EntitySelector<T>> allSelectors,
//      T sourceData
//  ) {
//    return switch (mainSelector) {
//      case EntitySelector.ByIdFromSession<T> _ -> Mono.deferContextual(ctx -> Mono.just(eventLogFromSession(entityModel, ctx)));
//      case EntitySelector.ById<T> s when s.creationMode() == AlwaysCreate -> Mono.just(EventLog.empty(s.id().apply(sourceData), entityModel));
//      case EntitySelector.ById<T> s -> eventLogByEntityId(entityModel, s.id().apply(sourceData));
//      case EntitySelector.BySecondaryId<T> s when s.creationMode() == AlwaysCreate ->
//          calculateNewIds(rootEntity, allSelectors, sourceData).map(newIds -> new EventLog(
//              entityModel,
//              newEntityId(),
//              newIds,
//              List.of()
//          ));
//      case EntitySelector.BySecondaryId<T> s ->
//          eventsByLookupId.execute(entityModel, new SecondaryId(s.model(), s.id().apply(sourceData)));
//      case EntitySelector.ByLastInIdGroup<T> s ->
//          eventsByLastEntity.execute(entityModel, s.model(), s.group().apply(sourceData), s.lastPosition());
//      case EntitySelector<?> s -> throw new IllegalStateException("Unexpected value: " + s);
//    };
//  }

  public <T> Mono<SecondaryId<T>> next(SecondaryIdModel<T> idModel, Object idGroup) {
    return lastSecondaryId.execute(idModel, idGroup)
        .map(id -> id.model().group().next(id))
        .switchIfEmpty(Mono.just(idModel.group().initial(idGroup)));
  }

//  public <T, I1, O1> Mono<ChangeSet<O1>> calculateNestedChange(
//      ZonedDateTime timestamp,
//      Entity rootEntity,
//      EventTrigger<T, I1, O1> eventTrigger,
//      T sourceData
//  ) {
//    List<? extends EntitySelector<T>> rawSelectors = eventTrigger.entitySelectors();
//    EntityModel entityModel = eventTrigger.entityModel();
//    EventType<I1, O1> eventType = eventTrigger.eventSpec().eventType();
//    I1 eventData = eventTrigger.eventSpec().inputAdapter().apply(sourceData);
//    return
//        (
//            rawSelectors.getFirst() instanceof EntitySelector.ByNextInIdGroup<T> s ?
//                next(rootEntity, s.model())
//                    .map(nextId -> (EntitySelector<T>) new BySecondaryId<T>(
//                        s.model(),
//                        _ -> nextId,
//                        s.creationMode()
//                    ))
//                    .mergeWith(Flux.fromIterable(rawSelectors.subList(
//                        1,
//                        rawSelectors.size()
//                    ))) :
//                Flux.fromIterable(rawSelectors)
//        ).collectList()
//        .flatMap(selectors -> fetchEventLogForNestedChange(rootEntity, entityModel, selectors.getFirst(), selectors, sourceData)
//            .flatMap(eventLog -> {
//              var currentState = begin(entityModel)
//                  .forward(eventLog.effectiveEvents().stream().map(Event::type).toList());
//              List<EventType<Void, ?>> scheduledEvents = scheduledEvents(entityModel, eventLog);
//              var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream()
//                  .toList());
//              if (stateAfterScheduledEvents == null) {
//                // TODO: Handle better. InconsistentState?
//                return Mono.error(new RuntimeException("Can't apply scheduled events " +
//                    scheduledEvents.stream().map(EventType::name).collect(joining(",")) +
//                    " to state " + currentState.state()));
//              }
//              return calculateChange(
//                  timestamp,
//                  eventLog,
//                  scheduledEvents,
//                  eventType,
//                  eventData,
//                  null,
//                  eventLog.events().isEmpty() ? eventLog.secondaryIds() : List.of()
//              )
//                  .flatMap(changeSet -> {
//                        if (changeSet.result().status() == Rejected && rawSelectors.getFirst().fallback() != null) {
//                          return calculateNestedChange(
//                              timestamp,
//                              rootEntity,
//                              eventTrigger.withFallbackSelector(),
//                              sourceData
////                              join(rawSelectors.getFirst().fallback(), rawSelectors.subList(1, rawSelectors.size())),
////                              entityModel,
////                              eventType,
////                              eventData
//                          );
//                        } else {
//                          return Mono.just(changeSet);
//                        }
//                      }
//                  );
//            })
//            .onErrorResume(EntityGroupNotInitialised.class, e -> {
//                  if (eventType instanceof BasicEventType.ReadOnly<?>) {
//                    return Mono.just(new ChangeSet<>(
//                        new ProcessResult(Accepted, null, null),
//                        List.of(),
//                        null
//                    ));
//                  } else if (selectors.getFirst().creationMode() == CreateIfNotExists) {
//                    return calculateNewIds(rootEntity, selectors, sourceData)
//                        .flatMap(newIds -> calculateChange(
//                            timestamp,
//                            emptyEventLog(entityModel), // New entity
//                            List.of(),
//                            eventType,
//                            eventData,
//                            null,
//                            newIds
//                        ));
//                  } else {
//                    return Mono.error(e);
//                  }
//                }
//            )
//            .onErrorResume(
//                UnknownEntity.class,
//                e -> {
//                  if (selectors.getFirst().fallback() != null) {
//                    return calculateNestedChange(
//                        timestamp,
//                        rootEntity,
//                        eventTrigger.withFallbackSelector(),
//                        sourceData

  /// /                        join(selectors.getFirst().fallback(), selectors.subList(1, selectors.size())), /
  ///               entityModel, /                        eventType, /                        eventData
//                    );
//                  } else if ((e.secondaryId() != null && isInitialInGroup(e.secondaryId())) || selectors.getFirst().creationMode() == CreateIfNotExists) {
//                    return calculateNewIds(rootEntity, selectors.subList(1, selectors.size()), sourceData)
//                        .flatMap(
//                            newIds -> calculateChange(
//                                timestamp,
//                                emptyEventLog(entityModel), // New entity
//                                List.of(),
//                                eventType,
//                                eventData,
//                                null,
//                                e.secondaryId() != null ? join(e.secondaryId(), newIds) : newIds
//                            )
//                        );
//                  } else {
//                    return Mono.error(e);
//                  }
//                }
//            )
//        );
//  }

//  private <T> Mono<List<SecondaryId>> calculateNewIds(
//      Entity rootEntity,
//      List<? extends EntitySelector<T>> entitySelectors,
//      T sourceData
//  ) {
//    return Flux.fromIterable(entitySelectors).flatMap(selector ->
//            switch (selector) {
//              case EntitySelector.BySecondaryId<T> s when s.model().isSerial() ->
//                  Mono.just(new SecondaryId(s.model(), s.id().apply(sourceData), 1));
//              case EntitySelector.BySecondaryId<T> s ->
//                  Mono.just(new SecondaryId(s.model(), s.id().apply(sourceData)));
//              case EntitySelector.ByLastInIdGroup<T> s ->
//                  lastSecondaryId.execute(s.model(), s.group().apply(sourceData))
//                      .switchIfEmpty(Mono.just(s.model().group().initial(s.group().apply(sourceData))));
//              case EntitySelector.ByNextInIdGroup<T> s -> next(rootEntity, s.model());
//              case null, default -> Mono.error(new IllegalStateException("Unhandled entity selector: " + selector));
//            }
//        )
//        .collectList();
//  }

//  private boolean isInitialInGroup(SecondaryId id) {
//    return id.model().group() != null && id.model().group().isInitial(id.data());
//  }

//  private List<EventType<Void, ?>> scheduledEvents(EntityModel entityType, EventLog eventLog) {
//    return begin(entityType).forward(eventLog.effectiveEvents().stream().map(Event::type).toList()).state().timeout()
//        .filter(timeout -> ZonedDateTime.now(clock)
//            .isAfter(eventLog.effectiveEvents().getLast().timestamp().plus(timeout.duration())))
//        .map(timeout -> List.<EventType<Void, ?>>of(timeout.event().eventType()))
//        .orElseGet(() -> {
//          List<EventType<Void, ?>> result = new ArrayList<>();
//          var effectiveEvents = eventLog.effectiveEvents();
//          TraversableState state = begin(entityType);
//          //List<ActualTransition<Void, ?, ?, ?>> list = new ArrayList<>();
//          for (var event : effectiveEvents) {
//            TransitionModel<Void, ?> tm = state.transition((EventType<Void, ?>)event.type());
//            if (tm == null) {
//              throw new IllegalStateException(String.format(
//                  "No transition found for event type %s from state %s",
//                  event.type(),
//                  state.state().name()
//              ));
//            }
//            result.addAll(
//                tm.scheduledEvents().stream()
//                    .filter(se -> !event
//                        .timestamp()
//                        .plus(se.deadline())
//                        .isAfter(ZonedDateTime.now(clock)))
//                    .map(ScheduledEvent::type)
//                    .toList()
//            );
//            state = state.forward(event.type());
//          }
//          return result;
//        });
//  }

//  public record ChangeSet<O>(ProcessResult<O> result, List<Change> changes) {
//
//    public static <O> ChangeSet<O> empty(ProcessResult<O> result) {
//      return new ChangeSet<>(result, List.of());
//    }
//
//  }

//  private ChangeSet<Void> merge(List<ChangeSet<?>> sets) {
//    return sets.stream()
//        .map(ChangeSet::result)
//        .filter(result -> !result.isAccepted())
//        .findFirst()
//        .map(processResult -> new ChangeSet<Void>(processResult, null, null))
//        .orElseGet(() -> new ChangeSet<>(
//            new ProcessResult.Accepted(),
//            sets.stream().flatMap(s -> s.changes().stream()).toList(),
//            null
//        ));
//  }

//  private <I, O> Mono<ChangeSet<O>> calculateChange(
//      ZonedDateTime timestamp,
//      String correlationId,
//      EventLog eventLog,
//      EventType<I, O> eventType,
//      I inputData,
//      IncomingMessage incomingMessage
//  ) {
//    var traverser = traversers.get(eventLog.entityModel());
//    var currentState = traverser.currentState(eventLog);
//    Mono<ChangeSet<?>> otherChangeSet = null;
//    if (eventType instanceof BasicEventType.Rollback rollbackType) {
//      otherChangeSet = calculateRollbackChanges(timestamp, correlationId, eventLog, new InputEvent<>(rollbackType, (Data) inputData));
//    }
//    TransitionModel<I, O> transitionModel = (TransitionModel<I, O>) traverser.accept(currentState, eventType); // TODO
//    if (transitionModel == null) {
//      return Mono.just(new ChangeSet<>(
//          new ProcessResult.Rejected(
//              "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:" + currentState
//                  + " does not accept " + eventType.name() + " (" + eventType.id() + ")"
//          ),
//          List.of(),
//          null
//      ));
//    }
//    Mono<ChangeSet<O>> changeSet = transitionModel.calculate(
//        eventLog.lastEventNumber() + 1,
//        currentState,
//        incomingMessage,
//        this,
//        clock,
//        new InputEvent<>(eventType, inputData),
//        eventLog,
//        timestamp,
//        correlationId
//    );
//    if (otherChangeSet == null)
//      return changeSet;
//    return otherChangeSet.flatMap(o -> !o.result().isAccepted() ?
//        Mono.just(new ChangeSet<>(o.result(), List.of(), null)) :
//        changeSet.map(c -> new ChangeSet<>(c.result(), join(c.changes(), o.changes()), c.output()))
//    );
//  }

//    inputTransition.eventNumber = eventLog.lastEventNumber() + scheduledEvents.size() + 1;
//    inputTransition.input = new InputEvent<>(eventType, inputData);
//    if (inputTransition.model == null) {
//      inputTransition.result = new ProcessResult(
//          Status.Rejected,
//          null,
//          "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:" + currentState.state()
//              + " does not accept " + Stream.concat(scheduledEvents.stream(), Stream.of(eventType))
//              .map(et -> et.name() + " (" + et.id() + ")")
//              .toList()
//      );
//      return Mono.just(new MultiTransition<>(
//          List.of(),
//          List.of(inputTransition),
//          inputTransition.output != null ? inputTransition.output.getUnmarshalledData() : null
//      ));
//    }
//    switch (incomingMessage) {
//      case Message.IncomingRequest rq -> inputTransition.incomingRequest = rq;
//      case Message.IncomingResponse rs -> inputTransition.incomingResponse = rs;
//    }

//    List<Transition<?, ?, ?, ?>> otherTransitions = new ArrayList<>();
//    TraversableState targetState;
//    if (eventType instanceof BasicEventType.Rollback) {
//      int rollbackToEventNumber = rollbackToEventNumber(eventLog);
//      if (rollbackToEventNumber < eventLog.lastEventNumber()) {
//        //targetState = traverseTo(eventLog.entityModel(), eventLog.events(), rollbackToEventNumber);
//        TraversableState state = traverseTo(rollbackToEventNumber, entity.model(), effectiveEventLog);
//        for (var event : eventLog.events().subList(rollbackToEventNumber, eventLog.events().size())) {
//          TransitionModel<?, ?, ?, ?> tm = state.transition(event.type());
//          if (tm == null) {
//            throw new IllegalStateException(String.format(
//                "No transition found for event type %s from state %s",
//                event.type(),
//                state.state().name()
//            ));
//          }
//          if (tm.reverse() != null) {
//            Transition<Void, ?, ?, ?> t = new Transition<>(timestamp, tm.reverse(), entity);
//            t.input = (InputEvent<Void>)new InputEvent<>(eventType, inputData); // It's a rollback
//            t.eventNumber = event.eventNumber();
//            otherTransitions.add(t);
//          }
//          state = state.forward(event.type());
//        }
//      } else {
//        // Rollback arrived before incoming request
//        //targetState = currentState;
//      }
//    } else {
//      var stateAfterScheduledEvents = currentState;
//      for (var et : scheduledEvents) {
//        stateAfterScheduledEvents = stateAfterScheduledEvents.forward(et);
//        if (stateAfterScheduledEvents == null) {
//          String errorMessage = "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:"
//              + currentState.state()
//              + " does not accept scheduled events " + scheduledEvents.stream().map(e -> e.name() + " (" + e.id() + ")")
//              .toList();
//          inputTransition.result = new ProcessResult(Status.Rejected, null, errorMessage);
//          return Mono.just(new MultiTransition<>(
//              List.of(),
//              List.of(inputTransition),
//              inputTransition.output != null ? inputTransition.output.getUnmarshalledData() : null
//          ));
//        }
//      }
//      targetState = stateAfterScheduledEvents.forward(eventType);
//      if (targetState == null) {
//        String errorMessage = "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:"
//            + stateAfterScheduledEvents.state()
//            + " does not accept event " + eventType.name() + " (" + eventType.id() + ")";
//        inputTransition.result = new ProcessResult(Status.Rejected, null, errorMessage);
//        return Mono.just(new MultiTransition<>(
//            List.of(),
//            List.of(inputTransition),
//            inputTransition.output != null ? inputTransition.output.getUnmarshalledData() : null
//        ));
//      }
//      TraversableState state = traverseTo(eventLog.lastEventNumber(), entity.model(), effectiveEventLog);
//      for (var event : createScheduledEvents(scheduledEvents, eventLog)) {
//        TransitionModel<Void, ?, ?, ?> tm = state.transition((EventType<Void, ?>)event.type());
//        if (tm == null) {
//          throw new IllegalStateException(String.format(
//              "No transition found for event type %s from state %s",
//              event.type(),
//              state.state().name()
//          ));
//        }
//        Transition<?, ?, ?, ?> t = new Transition<>(timestamp, tm, entity);
//        t.eventNumber = event.eventNumber();
//        //t.input = inputEvent; // transitions for scheduled events don't use input
//        otherTransitions.add(t);
//        state = state.forward(event.type());
//      }
//    }

//    return correlationId()
//        .flatMap(correlationId -> Flux.fromIterable(otherTransitions)
//            .flatMap(transition -> calculateChangeForTransition(transition, eventLog, correlationId))
//            .collectList()
//            .zipWith(calculateChangeForTransition2(inputTransition, eventLog, correlationId))
//            .map(otherAndInput -> new MultiTransition<>(
//                otherAndInput.getT1(),
//                List.of(otherAndInput.getT2()),
//                otherAndInput.getT2().output != null ? otherAndInput.getT2().output.getUnmarshalledData() : null
//            ))
//        );

    /*
    return calculateTriggerChange(inputTransition.model, entity, eventLog, inputEvent).flatMap(triggerChangeResult ->
        (inputEvent.eventType() instanceof BasicEventType.Rollback ?
            Mono.just(new TransitionWithData<>(inputTransition.model, null, eventNumber)) :
            createData(
                inputTransition.model,
                entity,
                eventLog,
                inputEvent,
                (Event<T>) triggerChangeResult.changes.getLast().newEvents().getLast() // [ChangeResult<T>]
            )
            .map(processingData -> new TransitionWithData<>(inputTransition.model, processingData, eventNumber))
            .switchIfEmpty(Mono.defer(() ->
                Mono.just(new TransitionWithData<>(inputTransition.model, null, eventNumber)
                ))
            )
    )
        .flatMap(newTransitionWithData -> {
          for (var f : newTransitionWithData.transitionModel().filters()) {
            if (!f.filter().test(newTransitionWithData.data())) {
              InputEvent<A> alternativeEvent = (InputEvent<A>) f.alternative().apply(newTransitionWithData.data());
              return calculateChange(
                  eventLog,
                  scheduledEvents,
                  alternativeEvent.eventType(),
                  alternativeEvent.data(),
                  inflightMessage,
                  idsForNewEntity
              );
            }
          }
          return Flux.concat(
                  Flux.fromIterable(otherTransitions)
                      .flatMap(transition -> transitionWithData(
                          (InputEvent<Void>) inputEvent,
                          transition,
                          entity,
                          eventLog,
                          null // TODO: triggeredEvent
                      )),
                  Flux.just(newTransitionWithData)
              ).collectList()
              .flatMap(transitionsWithData -> Flux.fromIterable(transitionsWithData)
                  .flatMap(actualTransition -> calculateNestedChanges(entity, inputEvent, eventLog, actualTransition.transitionModel().eventTriggers()))
                  .collectList()
                  .flatMap(changeResultList -> {
                    ChangeResult failedChangeResult = changeResultList.stream()
                        .map(ChangeResult::result)
                        .filter(not(ProcessResult::isAccepted))
                        .findFirst()
                        .map(negativeResult -> new ChangeResult(
                            new ProcessResult(
                                negativeResult.status(),
                                entity,
                                negativeResult.responseMessage(),
                                negativeResult.error()
                            ), null
                        ))
                        .orElse(null);
                    if (failedChangeResult != null)
                      return Mono.just(failedChangeResult);
                    List<ProcessResult> processResults = changeResultList.stream()
                        .map(ChangeResult::result)
                        .toList();
                    List<Event<?>> processedEvents = changeResultList.stream()
                        .flatMap(changeResult -> changeResult.changes().stream())
                        .flatMap(change -> change.newEvents().stream())
                        .toList();
                    List<Event<?>> newEvents = join(
                        createScheduledEvents(scheduledEvents, eventLog),
                        // rollback: new BasicEventType.Rollback.Data(rollbackToEventNumber(eventLog), "")
                        eventForInput(
                            inputEvent,
                            newTransitionWithData.data(),
                            newTransitionWithData.eventNumber(),
                            newTransitionWithData.transitionModel().outputTransformation(),
                            inflightMessage instanceof Message.IncomingRequest ir ? ir.messageId() : null,
                            inflightMessage instanceof Message.IncomingRequest ir ? ir.clientId() : null
                        )
                    );
                    return secondaryIdsToAdd(transitionsWithData).collectList()
                        .flatMap(secondaryIdsToAdd -> correlationId().flatMap(correlationId -> Flux.fromIterable(
                                    transitionsWithData)
                                .flatMap(transition0 -> outgoingRequests(
                                    transition0,
                                    inputEvent,
                                    new Entity(entity.id(), join(entity.secondaryIds(), secondaryIdsToAdd), entity.model()),
                                    newEvents,
                                    correlationId,
                                    processResults,
                                    processedEvents
                                )).collectList()
                                .zipWith(Flux.fromIterable(transitionsWithData)
                                    .flatMap(transition0 -> outgoingResponses(
                                        transition0,
                                        entity.id(),
                                        newEvents,
                                        inflightMessage,
                                        processResults,
                                        processedEvents
                                    )).collectList())
                            )
                            .flatMap(outgoingMessages -> correlationId().map(correlationId -> new ChangeResult(
                                new ProcessResult(
                                    Status.Accepted,
                                    new Entity(
                                        entity.id(),
                                        join(entity.secondaryIds(), secondaryIdsToAdd),
                                        entity.model()
                                    ),
                                    null,
                                    null
                                ),
                                join(
                                    changeResultList.stream()
                                        .map(ChangeResult::changes)
                                        .toList()
                                        .stream()
                                        .flatMap(List::stream)
                                        .toList(),
                                    new Change(
                                        eventLog.entityModel(),
                                        entityId,
                                        secondaryIds,
                                        currentState,
                                        targetState,
                                        newEvents,
                                        join(secondaryIdsToAdd, idsForNewEntity),
                                        inflightMessage instanceof Message.IncomingRequest m ? List.of(m) : List.of(),
                                        outgoingMessages.getT2(),
                                        outgoingMessages.getT1(),
                                        inflightMessage instanceof Message.IncomingResponse m ? List.of(m) : List.of(),
                                        getDeadline(targetState.state()),
                                        correlationId
                                    )
                                )
                            ))));

                  }));
        }));

     */

//  public Mono<ChangeSet<?>> calculateRollbackChanges(
//      ZonedDateTime timestamp,
//      String correlationId,
//      EventLog eventLog,
//      InputEvent<Data> rollbackEvent
//  ) {
//    System.out.println("calculateRollbackChanges to event number " + rollbackEvent.data().toNumber() + " on " + eventLog.entityModel().name() + " with log " + eventLog.events().stream().map(Event::typeName).collect(joining(",")));
//    int rollbackTo = rollbackEvent.data().toNumber();
//    if (rollbackTo < 0) {
//      rollbackTo = eventLog.lastEventNumber() + rollbackTo; // add negative
//    }
//    if (rollbackTo >= eventLog.lastEventNumber() || rollbackTo < 0) {
//      return Mono.just(ChangeSet.empty(ProcessResult.rejected(String.format(
//          "Can't rollback to event number %d (input=%d, last=%d)",
//          rollbackTo,
//          rollbackEvent.data().toNumber(),
//          eventLog.lastEventNumber()
//      ))));
//    }
//    var effectiveEvents = eventLog.effectiveEvents();
//    List<Event<?>> eventsToRollback = effectiveEvents.subList(rollbackTo, effectiveEvents.size());
//    System.out.println("Rolling back events on entity " + eventLog.entityModel().name() + "/" + eventLog.entityId().value() + ": " + eventsToRollback.stream().map(Event::typeName).collect(joining(", ")));
//
//    Traverser traverser = traversers.get(eventLog.entityModel());
//    Flux<ChangeSet<?>> changeSets = Flux.empty();
//    for (var event : eventsToRollback.reversed()) {
//      System.out.println("Rolling back " + event.typeName());
//      var tm = traverser.transitionForEventNumber(eventLog, event.eventNumber());
//      if (tm == null) {
//        throw new IllegalStateException(String.format("No transition found for event number %d", event.eventNumber()));
//      }
//      if (tm.reverseModel() != null) {
//        var reverseChangeSet = tm.reverseModel().calculateReverse(
//                event.eventNumber(),
//                this,
//                clock,
//                rollbackEvent,
//                eventLog,
//                timestamp,
//                correlationId
//            ).doOnNext(changeSet -> System.out.println(
//                "Calculated reversal for " + event.typeName() + "(#" + event.eventNumber() + "): "
//                    + changeSet.result() + "\n"
//                    + changeSet.changes.stream().map(c -> "  " + c).collect(joining("\n")))
//
//        );
//        changeSets = changeSets.mergeWith(reverseChangeSet);
//      }
//    }
//    return changeSets.collectList().map(this::merge);
//  }

  /*
  private <I, TO, P, O> Mono<Transition<?, ?, ?, ?>> calculateChangeForTransition(
      Transition<I, TO, P, O> transition,
      EventLog eventLog,
      String correlationId
  ) {
    transition.correlationId = correlationId;
    transition.deadline =
        transition.model.toState().timeout().map(timeout -> ZonedDateTime.now(clock).plus(timeout.duration())).orElse(null);
    return Mono.just(transition)
        // Trigger?
        .flatMap(t -> {
              if (t.model.eventTrigger() == null)
                return Mono.just(t);
              return calculateNestedChange(
                  transition.timestamp,
                  t.entity,
                  t.model.eventTrigger(),
                  tuple(t.input, eventLog)
              )
              .map(nestedResult -> {
                // TODO: Handle failed/rejected. Short cut here or at end?
                t.nestedTransition = nestedResult;
                return t;
              });
            }
        )
        // Assembled data?
        .flatMap(t -> createData(t.model, t.entity, eventLog, t.input, t.nestedTransition != null ? t.nestedTransition.output() : null)
            .map(data -> {
              t.assembledData = data;
              return t;
            })
            .switchIfEmpty(Mono.just(t)))
        // Identifiers
        .map(t -> {
          t.secondaryIds = t.model.newIdentifiers().stream().map(idf -> idf.apply(t.assembledData)).toList();
          return t;
        })
        // Outgoing requests
        .flatMap(t -> Flux.fromIterable(t.model.outgoingRequests())
            .flatMap(requestModel -> createOutgoingRequest(
                t.timestamp,
                t.entity,
                t.assembledData,
                t.input.eventType(),
                t.eventNumber,
                requestModel,
                correlationId
            )).collectList()
            .map(requests -> {
              t.outgoingRequests = requests;
              return t;
            })
        )
        // Outgoing response?
        .flatMap(t -> {
          if (t.model.outgoingResponse() == null)
            return Mono.just(t);
          return createOutgoingResponse(t, t.entity.id(), t.model.outgoingResponse())
              .map(outgoingResponse -> {
                t.outgoingResponse = outgoingResponse;
                return t;
              });
        })
        // Output event?
        .flatMap(t -> {
//          if (t.model.reverse())
//            return Mono.just(t);
          t.output = new Event<>(
              t.eventNumber,
              t.model.eventType(),
              clock,
              ofNullable(t.model.outputTransformation()).map(f -> f.apply(t.assembledData)).orElse(null)
          );
          return Mono.just(t);
        })
        // Gather result
        .map(t -> {
          t.result = new ProcessResult(
              Accepted,
              ofNullable(t.outgoingResponse).map(OutgoingResponse::message).orElse(null),
              null
          );
          return t;
        });
  }
*/
/*
  private <I, TO, P, O> Mono<Transition<I, ?, ?, O>> calculateChangeForTransition2(
      Transition<I, TO, P, O> transition,
      EventLog eventLog,
      String correlationId
  ) {
    transition.correlationId = correlationId;
    transition.deadline =
        transition.model.toState().timeout().map(timeout -> ZonedDateTime.now(clock).plus(timeout.duration())).orElse(null);
    return Mono.just(transition)
        // Trigger?
        .flatMap(t -> {
              if (t.model.eventTrigger() == null)
                return Mono.just(t);
              return calculateNestedChange(
                  transition.timestamp,
                  t.entity,
                  t.model.eventTrigger(),
                  tuple(t.input, eventLog)
              )
                  .map(nestedResult -> {
                    // TODO: Handle failed/rejected. Short cut here or at end?
                    t.nestedTransition = nestedResult;
                    return t;
                  });
            }
        )
        // Assembled data?
        .flatMap(t -> createData(t.model, t.entity, eventLog, t.input, t.nestedTransition != null ? t.nestedTransition.output() : null)
            .map(data -> {
              t.assembledData = data;
              return t;
            })
            .switchIfEmpty(Mono.just(t)))
        // Identifiers
        .map(t -> {
          t.secondaryIds = t.model.newIdentifiers().stream().map(idf -> idf.apply(t.assembledData)).toList();
          return t;
        })
        // Outgoing requests
        .flatMap(t -> Flux.fromIterable(t.model.outgoingRequests())
            .flatMap(requestModel -> createOutgoingRequest(
                t.timestamp,
                t.entity,
                t.assembledData,
                t.input.eventType(),
                t.eventNumber,
                requestModel,
                correlationId
            )).collectList()
            .map(requests -> {
              t.outgoingRequests = requests;
              return t;
            })
        )
        // Outgoing response?
        .flatMap(t -> {
          if (t.model.outgoingResponse() == null)
            return Mono.just(t);
          return createOutgoingResponse(t, t.entity.id(), t.model.outgoingResponse())
              .map(outgoingResponse -> {
                t.outgoingResponse = outgoingResponse;
                return t;
              });
        })
        // Output event?
        .flatMap(t -> {
//          if (t.model.reverse())
//            return Mono.just(t);
          t.output = new Event<>(
              t.eventNumber,
              t.model.eventType(),
              clock,
              ofNullable(t.model.outputTransformation()).map(f -> f.apply(t.assembledData)).orElse(null)
          );
          return Mono.just(t);
        })
        // Gather result
        .map(t -> {
          t.result = new ProcessResult(
              Accepted,
              ofNullable(t.outgoingResponse).map(OutgoingResponse::message).orElse(null),
              null
          );
          return t;
        });
  }
 */

  //  private TransitionWithData<Void, ?, ?, BasicEventType.Rollback.Data> rollbackTransitionWithData(
//      TransitionModel<Void, ?, ?, BasicEventType.Rollback.Data> transitionModelForInput,
//      EventLog eventLog,
//      int eventNumber,
//      EventType<Void, BasicEventType.Rollback.Data> eventType
//  ) {
//    return new TransitionWithData<>(
//            transitionModelForInput,
//            new Event<>(
//                eventNumber,
//                eventType,
//                clock,
//                new BasicEventType.Rollback.Data(rollbackToEventNumber(eventLog), "")
//            )
//        ),
//        null
//    );
//  }

//  private <I, O, P> Event<O> eventForInput(
//      InputEvent<I> inputEvent,
//      P data,
//      int eventNumber,
//      Function<P, O> outputTransformation,
//      String messageId,
//      String clientId
//  ) {
//    return new Event<>(
//        Integer.valueOf(eventNumber),
//        (EventType<I, O>)inputEvent.eventType(),
//        clock,
//        messageId,
//        clientId,
//        data != null ? outputTransformation.apply(data) : null
//    );
//  }
//
//  private <I, T, P, O> TransitionWithData<I, T, P, O> transitionWithData(
//      TransitionModel<I, T, P, O> transitionModelForInput,
//      int eventNumber,
//      EventType<I, O> eventType,
//      String messageId,
//      String clientId,
//      P processingData
//  ) {
//    return new TransitionWithData<>(transitionModelForInput, processingData, eventNumber);
//  }

//  private <I, T, P, O> Mono<TransitionWithData<I, T, P, O>> transitionWithData(
//      InputEvent<I> inputEvent,
//      ActualTransition<I, T, P, O> transition,
//      Entity entity,
//      EventLog eventLog,
//      Event<T> triggeredEvent
//  ) {
//    return createData(transition.model(), entity, eventLog, new InputEvent<>(inputEvent.eventType(), null), triggeredEvent)
//        .map(data -> new TransitionWithData<>(transition, data))
//        .defaultIfEmpty(new TransitionWithData<>(transition, null));
//  }

  public <O> Flux<Event<?>> onEvent(EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger);
  }

  public <O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<Void, Void, O> eventTrigger) {
    return onEvent(correlationId, eventTrigger, null, null);
  }

  public <I, O> Flux<Event<?>> onEvent(EventTrigger<I, I, O> eventTrigger, I input) {
    return onEvent(UUID.randomUUID().toString(), eventTrigger, input, null);
  }

  private String trace(EventTrigger<?, ?, ?> eventTrigger, String text) {
    return trace(eventTrigger.eventSpec().eventType(), eventTrigger.entityModel(), text);
  }

  private String trace(EventType<?, ?> eventType, EntityModel entityModel, String text) {
    try {
      return String.format(
          "%s on %s %s",
          eventType.name(),
          entityModel.name(),
          text
      );
    } catch (Exception e) {
      e.printStackTrace();
      return "trace logging failed: " + e.getMessage();
    }
  }

  public <T, I, O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<T, I, O> eventTrigger, T input) {
    return onEvent(correlationId, eventTrigger, input, null);
  }

  private <T, I, O> Flux<Event<?>> onEvent(String correlationId, EventTrigger<T, I, O> eventTrigger, T input, ChangeContext<?> stage1) {
    System.out.println("onEvent " + correlationId + " " + eventTrigger.eventSpec().eventType().name());
    I adaptedInput = eventTrigger.eventSpec().inputAdapter().apply(input);
    Many<Event<?>> responseSink = Sinks.many().unicast().onBackpressureBuffer();
    return eventLog(eventTrigger, input, null)
        .flatMapMany(log ->
                onEvent(correlationId, eventTrigger.eventSpec().eventType(), adaptedInput, log, null, stage1, List.of())
                    .contextWrite(ctx -> {
                      System.out.println("Putting " + log.entityModel().name() + " on context");
                          return ctx
                              //.put("RS/" + (tuple.t1().lastEventNumber() + 2) + "/" + tuple.t1().entityId().value(), responseSink)
                              .put("RS/" + log.entityId().value(), responseSink)
                              //.put(log.entityModel(), log.entityId())
                              ;
                        }
                    )
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
//                .onErrorResume(EventAlreadyExists.class, _ -> onEvent(correlationId, eventTrigger, input))
                    .switchIfEmpty(Flux.error(new RuntimeException("onEvent: No response")))
        );
  }

  private <I> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      InputEvent<I> inputEvent,
      EventLog eventLog,
      IncomingResponse inflightMessage,
      ChangeContext<?> requestChangeContext
  ) {
    return onEvent(correlationId, inputEvent.eventType(), inputEvent.data(), eventLog, inflightMessage, requestChangeContext, List.of());
  }

  private List<Change> changes(@NotNull ChangeContext<?> tail) {
    ArrayList<Change> changes = new ArrayList<>();
    for (var c = tail; c != null; c = c.previous()) {
      switch (c) {
        case OutputChangeContext<?> occ when occ.stepOutput().isAccepted() && !(occ.previous() instanceof ChangeContext.ChoiceChangeContext<?>) -> changes.add(Change.fromAcceptedEvent(occ.stepOutput().accepted()));
        case ChangeContext.OutputChangeContext<?> _ -> {}
        case ChangeContext.AssembledChangeContext<?> _ -> {}
        case ChangeContext.ChoiceChangeContext<?> _ -> {}
        case ChangeContext.InitialChangeContext<?> icc when icc.incomingResponse() != null -> changes.add(Change.fromIncomingResponse(icc.incomingResponse(), icc.log().entityModel(), icc.log().entityId()));
        case ChangeContext.InitialChangeContext<?> _ -> {}
        case ChangeContext.IdentityChangeContext<?> icc when icc.stepOutput().isAccepted() -> changes.add(Change.fromIdentifier(icc.stepOutput().accepted().id(), icc.entityModel(), icc.entityId()));
        case ChangeContext.IdentityChangeContext<?> _ -> {}
        case ChangeContext.OutgoingRequestChangeContext<?> orcc -> changes.add(Change.fromOutgoingRequest(orcc.outgoingRequest(), orcc.entityModel(), orcc.entityId()));
        case ChangeContext.PendingTriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.ScheduledChangeContext<?> _ -> {}
        case ChangeContext.TriggerChangeContext<?, ?> _ -> {}
        case ChangeContext.PendingChangeContext<?, ?, ?> _ -> {}
        case ChangeContext.CombinedChangeContext<?, ?> _ -> {}
        case ChangeContext.RejectedChangeContext<?> _ -> {}
        case ChangeContext.UnknownIdChangeContext<?> _ -> {}
      }
    }
    return changes;
  }

  private <I, O> Mono<ProcessResult<?>> onEvent(
      String correlationId,
      EventType<I, O> eventType,
      I input,
      EventLog eventLog,
      IncomingResponse incomingResponse,
      ChangeContext<?> stage1,
      List<IdentityResult<?>> identityResults
  ) {
    var now = ZonedDateTime.now(clock);
    var tuple = transitionModel(eventLog, eventType);
    if (tuple.t2() == null)
      return Mono.just(ProcessResult.rejected(
          eventType,
          new RejectedEvent(eventType, eventLog.entityModel(), eventLog.entityId(), tuple.t3())
      ));
    return tuple.t2().calculate(
            new InitialChangeContext<>(
                stage1,
                null,
                tuple.t2(),
                tuple.t3(),
                tuple.t1(),
                now,
                correlationId,
                input,
                this,
                incomingResponse,
                identityResults
            )
        )
        .doOnNext(output -> System.out.printf("onEvent result with pending unresolved:\n" + chainToString(output)))
        .flatMap(this::calculatePendingChanges)
        .doOnNext(output -> System.out.printf("onEvent result:\n" + chainToString(output)))
        .flatMap(output -> switch (output.stepOutput()) {
          case Accepted<?> _, Completed<?> _ -> storeChanges(now, correlationId, output)
              .thenReturn(output.stepOutput());
          case Rejected<?> r -> tuple.t2().rejectModel() == null ? Mono.error(r.exception()) :
              tuple.t2().rejectModel().calculate(
                  new InitialChangeContext<>(
                      null,
                      null,
                      tuple.t2(),
                      tuple.t3(),
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
                      incomingResponse,
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
        .onErrorResume(
            SecondaryIdAlreadyExists.class,
            e -> {
              return eventsByLookupId.execute(e.change().entityModel(), e.secondaryId())
                  .doOnNext(originalLog -> System.out.println(e.secondaryId().data() + " already exists. Original log: " + originalLog.events().stream().map(Event::typeName).collect(joining(","))))
                  .map(originalLog -> IdentityResult.rejected(e.secondaryId(), originalLog))
                  .flatMap(identityResult -> onEvent(correlationId, eventType, input, eventLog, incomingResponse, stage1, List.of(identityResult)));
//              return eventsByLookupId.execute(e.change().entityModel(), e.secondaryId())
//                  .flatMap(originalLog -> tuple.t2()
//                      .duplicateModel(e.secondaryId().model(), input, originalLog)
//                      .map(duplicateModel -> duplicateModel.calculate(
//                              new InitialChangeContext<>(
//                                  stage1,
//                                  null,
//                                  tuple.t2(),
//                                  tuple.t3(),
//                                  tuple.t1(),
//                                  now,
//                                  correlationId,
//                                  tuple(input, originalLog),
//                                  this,
//                                  incomingResponse,
//                                  List.of()
//                              )
//                          ).flatMap(duplicateChanges -> duplicateChanges.stepOutput().isAccepted() ?
//                              storeChanges(now, correlationId, duplicateChanges).thenReturn(duplicateChanges.stepOutput()) :
//                              Mono.error(new RuntimeException(
//                                  "Failed to handle event with duplicate id: " + duplicateChanges.stepOutput()))
//                          )
//                      )
//                      .orElse(Mono.error(e))
//                  );
            }
        );
  }

  private String chainToString(ChangeContext<?> tail) {
    StringBuilder b = new StringBuilder();
    for (ChangeContext<?> c = tail; c != null; c = c.previous()) {
      b.append(c).append("\n");
    }
    return b.toString();
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
    return calculateTriggeredEvent(p.stepOutput().trigger(), previous, p.stepOutput().inputData(), false);
  }


  public Mono<State> onStatus(EntitySelector<Void> entitySelector, EntityModel entityModel) {
    return eventLog(entitySelector, null, entityModel, null)
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
        eventLog(eventTrigger.entitySelectors().getFirst().apply(inputData), inputData, eventTrigger.entityModel(), changeContext);
  }

  private EventLog logFromNestedChanges(EntityId entityId, ChangeContext<?> changeContext) {
    // TODO: Duplicates changes(ChangeContext<?>) a bit..
    System.out.println("Finding log from change context for entity id " + entityId.value());
    for (var c = changeContext; c != null; c = c.previous()) {
      if (c instanceof OutputChangeContext<?>(TransitionModelBuilder.ChangeContext<?> previous, ProcessResult<?> stepOutput)
          && stepOutput instanceof Accepted<?>(Event<?> event, _)
          && event.entityId().equals(entityId.value())
          && !(previous instanceof ChoiceChangeContext)) {
        return c.initialChangeContext().log().withNewEvent(event);
      }
    }
    return null;
//    List<Change> changes = nestedChanges.stream()
//        .flatMap(changeSet -> changeSet.changes().stream())
//        .filter(change -> change.eventLog().entityId().equals(entityId))
//        .sorted(comparing(change -> change.newEvent().eventNumber()))
//        .toList();
//    System.out.println("Found log from nested changes: " + (changes.isEmpty() ? "No" : (changes.getLast().eventLog().entityModel().name() + ": " + changes.getLast().eventLog().events().stream().map(Event::typeName).collect(joining(",")))));
//    return changes.isEmpty() ? null : changes.getLast().eventLog().withNewEvent(changes.getLast().newEvent());
  }

  private <I> Mono<EventLog> eventLog(
      EntitySelector<I> entitySelector,
      I inputData,
      EntityModel entityModel,
      @Nullable ChangeContext<?> changeContext
  ) {
    System.out.println("Finding event log for " + entityModel.name() + " with selector " + entitySelector);
    return switch (entitySelector) {
      case EntitySelector.ByIdFromSession<I> _ -> Mono.deferContextual(ctx ->
              Mono.just(entityIdFromChangeContext(changeContext, entityModel))
//              Mono.justOrEmpty(ctx.<EntityId>getOrEmpty(entityModel))
//                  .switchIfEmpty(Mono.error(new RuntimeException(
//                      "No id found in session for entity type " + entityModel.name())))
//                  .doOnNext(entityId -> System.out.println("Found entity id for entity type " + entityModel.name() + " in session: " + entityId.value()))
                  .flatMap(entityId -> Mono.justOrEmpty(ctx.<EventLog>getOrEmpty(entityId))
                      .doOnNext(eventLog -> System.out.println("Found event log for entity " + entityModel.name() + "/" + entityId.value() + " in session: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))))
                          .switchIfEmpty(Mono.justOrEmpty(logFromNestedChanges(entityId, changeContext)))
                          .switchIfEmpty(eventLogByEntityId(entityModel, entityId))
                  )
      );
      case EntitySelector.ById<I> s -> {
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
      case EntitySelector.BySecondaryId<I, ?> selector -> switch (selector.creationMode()) {
        case NeverCreate -> eventsByLookupId.execute(entityModel, secondaryId(selector, inputData))
            .onErrorResume(
                UnknownEntity.class,
                e -> selector.fallback() != null ? eventLog(selector.fallback(), inputData, entityModel, changeContext)
                    : Mono.error(e)
            );
        case CreateIfNotExists -> eventsByLookupId.execute(entityModel, secondaryId(selector, inputData))
            .onErrorResume(UnknownEntity.class, _ -> Mono.just(emptyEventLog(entityModel)));
        case AlwaysCreate -> Mono.just(emptyEventLog(entityModel));
      };
      case EntitySelector.ByLastInIdGroup<I, ?> s -> switch (s.creationMode()) {
        case AlwaysCreate -> throw new IllegalStateException("Unexpected value: " + s.creationMode());
        case CreateIfNotExists ->
            eventsByLastEntity.execute(entityModel, s.model(), s.group().apply(inputData), s.lastPosition())
                .onErrorResume(EntityGroupNotInitialised.class, _ -> Mono.just(emptyEventLog(entityModel)));
        case NeverCreate ->
            eventsByLastEntity.execute(entityModel, s.model(), s.group().apply(inputData), s.lastPosition());
      };
//      case EntitySelector.ByNextInIdGroup<I, ?> s -> switch (s.creationMode()) {
//        case AlwaysCreate -> null;
//        case CreateIfNotExists -> null;
//        case NeverCreate -> null;
//      };
      case EntitySelector<I> s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private <I, T> SecondaryId<T> secondaryId(BySecondaryId<I, T> selector, I inputData) {
    return new SecondaryId<>(selector.model(), selector.id().apply(inputData));
  }

  private EntityId entityIdFromChangeContext(ChangeContext<?> context, EntityModel entityModel) {
    for (var c = context; c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i && i.log().entityModel().equals(entityModel)) {
        return i.log().entityId();
      }
    }
    InitialChangeContext<?> initial = null;
    for (var c = context; c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i) initial = i;
    }
    System.out.println("entityIdFromChangeContext - found nothing. Is there a requestChangeContext? " + initial.stage1() != null);
    for (var c = initial.stage1(); c != null; c = c.previous()) {
      if (c instanceof ChangeContext.InitialChangeContext<?> i && i.log().entityModel().equals(entityModel)) {
        return i.log().entityId();
      }
    }
    throw new NoSuchElementException(entityModel.name() + " not found in change context");
  }

  public <T, I, O> Mono<ChangeContext<ProcessResult<O>>> calculateTriggeredEvent(
      EventTrigger<T, I, O> eventTrigger,
      ChangeContext<?> previous,
      T data,
      boolean skipCircularCheck
  ) {
    I adaptedData = eventTrigger.eventSpec().inputAdapter().apply(data);
    return Mono.deferContextual(ctx -> {
          EntityId entityId = switch (eventTrigger.entitySelectors().getFirst().apply(data)) {
            case EntitySelector.ByIdFromSession<T> _ -> {
              try {
                yield entityIdFromChangeContext(previous, eventTrigger.entityModel());
                //yield ctx.get(eventTrigger.entityModel());
              } catch (NoSuchElementException e) {
                throw new RuntimeException(e.getMessage() + "\nFound in Reactor context:\n" +
                    ctx.stream().map(x -> x.toString()).collect(joining("\n")));
              }
            }
            case EntitySelector.ById<T> s -> s.id();
            default -> null;
          };
          if (!skipCircularCheck && ctx.hasKey(eventTrigger.entityModel())) {
            if (ctx.get(eventTrigger.entityModel()).equals(entityId)) {
              throw new CircularChange(
                  new InputEvent<>(eventTrigger.eventSpec().eventType(), adaptedData),
                  eventTrigger.entityModel(),
                  entityId
              );
            }
          }
          return Mono.just(ctx);
        }
    ).flatMap(_ -> eventLog(eventTrigger, data, previous)
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
                    previous,
                    tuple.t2(),
                    tuple.t3(),
                    tuple.t1(),
                    previous.initialChangeContext().timestamp(),
                    previous.initialChangeContext().correlationId(),
                    adaptedData,
                    previous.initialChangeContext().stateMachine(),
                    null,
                    List.of()
                )
            ))
    );
  }

  public static class CircularChange extends RuntimeException {

    private final InputEvent<?> inputEvent;
    private final EntityModel entityModel;
    private final EntityId entityId;

    public CircularChange(InputEvent<?> inputEvent, EntityModel entityModel, EntityId entityId) {
      super(String.format(
          "%s on %s/id=%s is circular",
          inputEvent.eventType().name(),
          entityModel.name(),
          entityId.value().toString()
      ));
      this.inputEvent = inputEvent;
      this.entityModel = entityModel;
      this.entityId = entityId;
    }
  }

  public static class RejectedEvent extends RuntimeException {

    private final EventType<?, ?> eventType;
    private final EntityModel entityModel;
    private final EntityId entityId;
    private final State currentState;

    public RejectedEvent(EventType<?, ?> eventType, EntityModel entityModel, EntityId entityId, State currentState) {
      super(String.format(
          "%s on %s not allowed for %s",
          eventType != null ? eventType.name() : "<no event type>",
          entityModel != null ? entityModel.name() : "<no entity model>",
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
          "%s on %s not allowed for %s: Can't rollback to event number %d",
          eventType != null ? eventType.name() : "<no event type>",
          entityModel != null ? entityModel.name() : "<no entity model>",
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
    var currentStateAndTransition = traverser.accept(eventLog, eventType);
//    var currentState = traverser.currentState(eventLog);
//    // TODO
//    var modelForCurrentTransition = (TransitionModel<I, O>)traverser.accept(currentState, eventType);
//    return tuple(eventLog, modelForCurrentTransition, currentState);
    return tuple(eventLog, (TransitionModel<I, O>) currentStateAndTransition.t2(), currentStateAndTransition.t1());
  }

  public <I, T> Mono<? extends ChangeContext<ProcessResult<T>>> calculateChange(
      EventType<I, T> eventType,
      EventLog eventLog,
      ZonedDateTime timestamp,
      String correlationId
  ) {
    Traverser traverser = traversers.get(eventLog.entityModel());
    var currentStateAndTransition = traverser.accept(eventLog, eventType);
//    State currentState = traverser.currentState(eventLog);
//    TransitionModel<I, T> transitionModel = (TransitionModel<I, T>)traverser.accept(currentState, eventType); // TODO
    //return transitionModel.calculate(
    return ((TransitionModel<I, T>) currentStateAndTransition.t2()).calculate(
        new InitialChangeContext<>(
            null,
            null,
            currentStateAndTransition.t2(),
            currentStateAndTransition.t1(),
            eventLog,
            timestamp,
            correlationId,
            null,
            this,
            null,
            List.of()
        )
    );
  }

//  private <I> Mono<ProcessResult> processEvents(
//      EventLog eventLog,
//      InputEvent<I> inputEvent,
//      IncomingMessage inflightMessage,
//      String correlationId
//  ) {
//    ZonedDateTime timestamp = ZonedDateTime.now(clock);
//    return calculateChange(
//        timestamp,
//        correlationId,
//        eventLog,
//        inputEvent.eventType(),
//        inputEvent.data(),
//        inflightMessage
//    )
//        .switchIfEmpty(Mono.error(new IllegalStateException("Empty change result for event " + inputEvent)))
//        .flatMap(result -> switch (result.result()) {
//              case Accepted r -> storeChanges(timestamp, result.changes());
//              case ProcessResult r -> Mono.just(r);
//            }
//        )
//        .onErrorResume(t -> Mono.just(new ProcessResult.Failed(toString(t))));
//  }

  private List<Listener.Change> toListenerFormat(List<Change> changes) {
    return changes.stream()
        .filter(Change::storeEvent)
        .map(change -> new Listener.Change(
                new Listener.Change.Entity(
                    change.entityModel().name(),
                    change.entityId().value(),
                    change.newSecondaryIds().stream().map(id -> id.model().name() + ":" + id.data()).toList()
                ),
                change.deadline(),
                change.newEvent() != null ? new Listener.Change.Event(
                    change.newEvent().eventNumber(),
                    change.newEvent().type().name(),
                    change.newEvent().data()
                ) : null,
                change.newSecondaryIds().stream().map(id -> id.model().name() + ":" + id.data()).toList(),
                //change.incomingRequest() != null ? List.of(change.incomingRequest().message().requestLine()) : List.of(),
                //change.outgoingResponse() != null ? List.of(change.outgoingResponse().message().statusLine()) : List.of(),
                change.outgoingRequests().stream()
                    .map(r -> r.message().message().substring(0, Math.min(1000, r.message().message().length())))
                    .toList(),
                change.incomingResponse() != null ?
                    List.of(change.incomingResponse().message().message()
                            .substring(0, Math.min(1000, change.incomingResponse().message().message().length()))) :
                    List.of()
            )
        )
        .toList();
  }

//  private static <E> List<E> join(List<E> list, E element) {
//    var l = new ArrayList<E>(list.size() + 1);
//    l.addAll(list);
//    l.add(element);
//    return unmodifiableList(l);
//  }
//
//  private <E> List<E> join(E element, List<E> list) {
//    var l = new ArrayList<E>(list.size() + 1);
//    l.add(element);
//    l.addAll(list);
//    return unmodifiableList(l);
//  }

  private <E> List<E> join(List<E> list1, List<E> list2) {
    var l = new ArrayList<E>(list1.size() + list2.size());
    l.addAll(list1);
    l.addAll(list2);
    return unmodifiableList(l);
  }

  private Mono<Void> storeChanges(
      ZonedDateTime timestamp,
      String correlationId,
      ChangeContext<?> changeContext
  ) {
    List<Change> changes = changes(changeContext);
    //    System.out.println("storeChanges " + unfilteredChanges.stream().map(c -> c.newEvent() != null ? c.eventLog().entityModel().name() + "/" + c.newEvent().typeName() : "N/A").collect(Collectors.joining(", ")));
    System.out.println("Changes to store: " + changes.stream().map(c -> c.toString()).collect(joining("\n")));
//    List<Change> storableChanges = changes.stream()
    // TODO: Changes for ReadOnly events which have side effects must still be stored. Do we need ReadOnly events
    //       when Status doesn't use event anymore.
    //.filter(change -> change.newEvent() == null || !(change.newEvent().type() instanceof BasicEventType.ReadOnly))
//        .toList();
//    if (changes.isEmpty()) return Mono.just(ProcessResult.accepted());
    return Mono.just("") //correlationId()
        // TODO: Not really handling multiple changes at once
//        .delayUntil(c -> changes.getLast().newEvent() == null ?
//            Mono.empty() :
//            delayer.apply(List.of(c + "-" + changes.getLast().newEvent().typeName()))
//        )
        .delayUntil(_ -> delayer.apply(changes.stream()
            .filter(change -> change.newEvent() != null)
            .map(change -> correlationId + "-" + change.newEvent().typeName())
            .toList())
        )
        .flatMap(_ -> changeState.execute(
                    timestamp,
                    correlationId,
                    changes.stream()
                        //.filter(c -> c.newEvent() == null || !(c.newEvent().type() instanceof BasicEventType.ReadOnly))
                        .toList()
                )
                .collectList()
//            .doOnNext(outboxElements -> System.out.println("To forward:\n" + outboxElements.stream().map(e -> e.toString()).collect(joining("\n"))))
                .flatMap(x -> Mono.just("")
                    .contextWrite(ctx -> {
                          changes.stream()
                              .filter(change -> change.newEvent() != null && change.storeEvent())
                              .collect(Collectors.groupingBy(
                                  Change::entityId,
                                  toList()
                              ))
                              .forEach((entityId, changeList) -> {
                                changeList.sort(comparing(change -> change.newEvent().eventNumber()));
                                ctx.<Many<Event<?>>>getOrEmpty("RS/" + entityId.value())
                                    .map(responseSink -> {
                                      System.out.println("Sending events to response sink for changes:\n" + changeList.stream()
                                          .map(Object::toString)
                                          .collect(joining("\n")));
                                      changeList.forEach(change -> responseSink.tryEmitNext(change.newEvent()).orThrow());
                                      return responseSink;
                                    });
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
                .transformDeferredContextual((publisher, ctx) -> publisher
                    .doOnNext(outboxElementsToForward -> outboxElementsToForward.forEach(q ->
                            forwardInitial(
                                changeContext,
                                changes.get(q.changeIndex()),
                                q.elementId(),
                                q.requestId(),
                                outgoingRequest(changes.get(q.changeIndex()), q.changeIndex(), q.messageIndex()),
                                correlationId,
                                timestamp
                            ).contextWrite(ctx).subscribe()
                        )
                    )
                )
                .then()
        );
  }

  private OutgoingRequest outgoingRequest(Change change, int changeIndex, int messageIndex) {
    try {
      return change.outgoingRequests().get(messageIndex);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to get outgoing request for change index " + changeIndex + " and message index " + messageIndex
              + ". Change: " + change,
          e
      );
    }
  }

  private Mono<ForwardStatus> forwardInitial(
      ChangeContext<?> changeContext,
      Change change,
      byte[] queueElementId,
      UUID requestId,
      OutgoingRequest outgoingRequest,
      String correlationId,
      ZonedDateTime timestamp
  ) {
//    System.out.println(
//        "forwardInitial: " + change.eventLog().entityModel().name() + "/" + change.eventLog().entityId().value() + "/"
//            + (change.newEvent() == null ? "<no event>" : change.newEvent().type().name()) + "/"
//            + (change.newEvent() == null ? "<no event number>" : change.newEvent().eventNumber()));
    var queueElement = new OutboxElement(
        queueElementId,
        requestId,
        change.entityId(),
        change.entityModel(),
        outgoingRequest.eventNumber(),
        outgoingRequest.creatorId(),
        outgoingRequest.queue(),
        outgoingRequest.guaranteed(),
        timestamp,
        outgoingRequest.message(),
        correlationId,
        1,
        null,
        null
    );
    return doForward(changeContext, queueElement, outgoingRequest.maxRetryAttempts(), outgoingRequest.retryInterval());
  }

  Mono<ForwardStatus> forward(OutboxElement queueElement) {
//    System.out.println("forward: " + queueElement.entityModel().name() + "/" + queueElement.entityId().value() + "/" + queueElement.eventNumber());
    return doForward(null, queueElement, 0, null).contextWrite(Correlation.contextOf(queueElement.correlationId()));
  }

  private record ResponseValidationResult(Result validationResult, Message.IncomingResponse response) {}

  private Mono<ForwardStatus> doForward(@Nullable ChangeContext<?> requestChangeContext, OutboxElement queueElement, int maxRetryAttempts, Duration retryInterval) {
    OutgoingRequestCreator<?> c = outgoingRequestCreators.get(queueElement.creatorId());
    Mono<HttpRequestMessage> requestMessage = c != null && queueElement.attempt() > 1 ?
        c.repeatedReactive(queueElement.data()) :
        Mono.just(queueElement.data());
    return requestMessage.zipWith(eventsByEntityId.execute(queueElement.entityModel(), queueElement.entityId()))
        .flatMap(requestAndEventLog -> {
          HttpRequestMessage request = requestAndEventLog.getT1();
          EventLog eventLog = requestAndEventLog.getT2();
          var responseValidator = findOutgoingRequestModel(
              eventLog,
              queueElement.eventNumber(),
              queueElement.queue(),
              queueElement.creatorId()
          ).responseValidator();
          return clients.apply(queueElement.queue()).exchange(request)
              .flatMap(responseMessage -> {
                var responseMessageOnQueue = responseMessageOnQueue(queueElement, responseMessage);
                return validateResponse(
                    request,
                    responseMessage,
                    responseValidator,
                    queueElement.entityId(),
                    eventLog.lastEventNumber() + 1
                ).map(output -> new ResponseValidationResult(output, responseMessageOnQueue));
              })
              .flatMap(validationOutput -> validationOutput.validationResult().status() == Status.TransientError ?
                  Mono.error(new TransientError(validationOutput)) :
                  Mono.just(validationOutput)
              )
              .retryWhen(RetrySpec.fixedDelay(maxRetryAttempts, retryInterval)
                  .filter(e -> e instanceof TransientError && maxRetryAttempts > 0))
              .onErrorResume(TransientError.class, e -> Mono.just(e.transientResult))
              .flatMap(validationOutput ->
                  (validationOutput.validationResult().inputEvent() != null ?
                      onEvent(
                          queueElement.correlationId(),
                          validationOutput.validationResult().inputEvent(),
                          eventLog,
                          validationOutput.response,
                          requestChangeContext
                      )
//                      processEvents(
//                          eventLog,
//                          validationOutput.validationResult().inputEvent(),
//                          validationOutput.response(),
//                          queueElement.correlationId()
//                      )
                      .flatMap(processResult -> processResult instanceof Raced ?
                          Mono.error(new ProcessEventsRaced()) :
                          Mono.just(processResult)
                      )
                      .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(100))
                                 .filter(e -> e instanceof ProcessEventsRaced))
//                          .flatMap(processResult -> Mono.deferContextual(ctx -> {
//                            if (processResult.responseMessage() != null && hasResponseSink(ctx)) {
//                              One<HttpResponseMessage> sink = responseSink(ctx);
//                              sink.tryEmitValue(processResult.responseMessage());
//                            }
//                            return Mono.just(processResult);
//                          }))
                      .map(processResult -> new IncomingResponseStatus(
                          validationOutput.response(),
                          processResult,
                          validationOutput.validationResult()
                      )) :
                      Mono.just(new IncomingResponseStatus(
                              validationOutput.response(),
                              ProcessResult.rejected(
                                  null,
                                  new RejectedEvent(null, queueElement.entityModel(), eventLog.entityId(), null)
                              ),
                              validationOutput.validationResult()
                          )
                      )
                  ))
              .flatMap(result -> switch (result.processResult()) {
                    case Accepted _ -> Mono.just(ForwardStatus.Ok);
                    case Rejected r -> switch (result.validationResult().status()) {
                      case Ok -> dequeueAndStoreReceipt.execute(
                              queueElement,
                              result.response().message(),
                              ZonedDateTime.now(clock)
                          )
                          .thenReturn(ForwardStatus.Ok)
                          .doOnSuccess(_ -> logForwarded(
                              queueElement,
                              result.response().message(),
                              "Forwarded and dequeued, as response event " +
                                  (result.validationResult().inputEvent() != null ?
                                      result.validationResult().inputEvent().eventType().name() :
                                      "N/A"
                                  ) + " was rejected: " + r.exception.getMessage()
                          ));
                      case PermanentError -> moveToDLQ.execute(queueElement, result.validationResult().message())
                          .doOnSuccess(_ -> logDead(queueElement, result.validationResult().message()))
                          .thenReturn(ForwardStatus.Ok);
                      case TransientError -> backOffOrDie(
                          queueElement,
                          requireNonNullElse(result.validationResult().message(), "TransientError")
                      ).thenReturn(ForwardStatus.Ok);
                    };
                    case Raced r -> Mono.error(new IllegalStateException("Raced response not handled"));
                    case Failed r -> backOffOrDie(queueElement, r.reason()).thenReturn(ForwardStatus.Ok);
                    case ProcessResult r -> Mono.error(new IllegalStateException("Unexpected value: " + r));
                  }
              );
        })
        .onErrorResume(e -> backOffOrDie(queueElement, e));
  }

  private static class ProcessEventsRaced extends RuntimeException {}

  private OutgoingRequestModel<?, ?> findOutgoingRequestModel(
      EventLog eventLog,
      int eventNumber,
      OutboxQueue queue,
      UUID requestModelId
  ) {
    TransitionModel<?, ?> transitionForEvent = traversers.get(eventLog.entityModel())
        .transitionForEventNumber(eventLog, eventNumber);
    return Stream.concat(
            transitionForEvent.outgoingRequests().stream(),
            ofNullable(transitionForEvent.reverseModel()).stream()
                .flatMap(reverseTransition -> reverseTransition.outgoingRequests().stream())
        )
        .filter(model -> {
              if (!model.queue().equals(queue))
                return false;
              OutgoingRequestCreator<?> c = model.creator();
              if (c == null)
                c = beanRegistry.getBean(model.creatorType());
              return c.id().equals(requestModelId);
            }
        )
        .findFirst()
        .orElseThrow(() -> new RuntimeException(String.format(
            "No outgoing request model found for %s:%s with event number %d on queue %s",
            eventLog.entityModel().name(),
            eventLog.entityId().value(),
            eventNumber,
            queue.name()
        )));
  }

  private static class TransientError extends RuntimeException {

    ResponseValidationResult transientResult;

    public TransientError(ResponseValidationResult transientResult) {
      this.transientResult = transientResult;
    }
  }

  private Message.IncomingResponse responseMessageOnQueue(
      OutboxElement queueElement,
      HttpResponseMessage responseMessage
  ) {
    return new Message.IncomingResponse(
        // Synchronous response will always trigger an event following directly the request event
        queueElement.eventNumber() + 1,
        responseMessage,
        queueElement.requestId(),
        queueElement.queue(),
        queueElement.guaranteed()
    );
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
        queueElement.enqueuedAt(),
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
    listener.forwardingDeadByExhaustion(
        e.requestId(),
        e.entityModel(),
        e.queue().name(),
        e.entityId(),
        e.eventNumber(),
        e.enqueuedAt(),
        e.attempt(),
        e.correlationId(),
        reason
    );
  }

  private void logForwarded(OutboxElement e, HttpResponseMessage responseMessage, String reason) {
    listener.forwardingCompleted(
        e.requestId(),
        e.entityModel(),
        e.queue().name(),
        e.entityId(),
        e.eventNumber(),
        e.enqueuedAt(),
        e.attempt(),
        e.correlationId(),
        responseMessage,
        reason
    );
  }

  private void logBackoff(OutboxElement e, String reason) {
    // TODO: e.backoff() requires processedAt nextAttemptAt
    listener.forwardingBackedOff(
        e.requestId(),
        e.entityModel(),
        e.queue().name(),
        e.entityId(),
        e.eventNumber(),
        e.enqueuedAt(),
        e.attempt(),
        e.correlationId(),
        reason,
        e.nextAttemptAt(),
        e.backoff()
    );
  }

  private void logDead(OutboxElement e, String reason) {
    listener.forwardingDead(
        e.requestId(),
        e.entityModel(),
        e.queue().name(),
        e.entityId(),
        e.eventNumber(),
        e.enqueuedAt(),
        e.attempt(),
        e.correlationId(),
        reason
    );
  }

//  private <I, T, P, O> Flux<OutgoingRequest> outgoingRequests(
//      Transition<I, T, P, O> transition,
//      Entity entity,
//      List<Event<?>> newEvents,
//      String correlationId,
//      List<ProcessResult> processResults,
//      List<Event<?>> processedEvents
//  ) {
//    return Flux.fromIterable(transition.model.outgoingRequests())
//        .flatMap(outgoingRequestModel -> createOutgoingRequest(
//            transition,
//            outgoingRequestModel,
//            correlationId,
//            processResults,
//            processedEvents
//        ));
//  }

//  private <I, T, P, O> Flux<OutgoingResponse> outgoingResponses(
//      TransitionWithData<I, T, P, O> transition,
//      EntityId entityId,
//      List<Event<?>> newEvents,
//      Message inflightMessage,
//      List<ProcessResult> processResults,
//      List<Event<?>> processedEvents
//  ) {
//    return Flux.fromIterable(transition.transitionModel().outgoingResponses())
//        .flatMap(outgoingResponseModel -> createOutgoingResponse(
//            transition,
//            entityId,
//            newEvents,
//            outgoingResponseModel,
//            inflightMessage
//        ));
//  }

//  private Flux<SecondaryId> secondaryIdsToAdd(
//      List<TransitionWithData<?, ?, ?, ?>> transitionsWithData
//  ) {
//    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
//    for (var actualTransition : transitionsWithData) {
//      secondaryIdFlux = secondaryIdFlux.mergeWith(secondaryIdsToAdd(actualTransition));
//    }
//    return secondaryIdFlux;
//  }

//  private <I, T, P, O> Flux<SecondaryId> secondaryIdsToAdd(TransitionWithData<I, T, P, O> transitionWithData) {
//    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
//    secondaryIdFlux = secondaryIdFlux.mergeWith(Flux
//        .fromIterable(transitionWithData.transitionModel().newIdentifiers())
//        .map(newId -> newId.apply(transitionWithData.data())));
//    return secondaryIdFlux;
//  }

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

  public <P, U> Mono<OutgoingRequest> createOutgoingRequest(
      boolean reverse,
      ZonedDateTime timestamp,
      Entity entity,
      P assembledData,
      int eventNumber,
      ChangeContext<?> previous,
      OutgoingRequestModel<P, U> model,
      String correlationId
  ) {
    OutgoingRequestCreator<U> creator = model.creatorType() != null ?
        beanRegistry.getBean(model.creatorType()) :
        model.creator();
    System.out.println(
        "createOutgoingRequest (" + (reverse ? "<reverse>" : "") + ") for event number " + eventNumber + " on "
            + entity.model.name() + " with " + creator.getClass().getSimpleName());
    EntityId parentEntity = parentEntityId(previous, entity.model().parentEntity());
    return (reverse ?
        outgoingRequestByEvent.execute(entity.id(), eventNumber, model.queue())
        .switchIfEmpty(creator.reversedReactive(
            model.dataAdapter().apply(assembledData),
            reversalContext(null, entity.id(), correlationId, timestamp)
        ))
        .flatMap(originalMessage -> creator.reversedReactive(
            model.dataAdapter().apply(assembledData),
            reversalContext(originalMessage, entity.id(), correlationId, timestamp)
        )) :
        creator.createReactive(model.dataAdapter().apply(assembledData), context(entity.id(), correlationId, timestamp))
        .switchIfEmpty(Mono.error(new IllegalStateException()))
    ).map(message -> new OutgoingRequest(
        UUID.randomUUID(),
        eventNumber,
        message,
        model.queue(),
        creator.id(),
        model.guaranteed(),
        model.maxRetryAttempts(),
        model.retryInterval(),
        parentEntity
    ));
  }

//  public <P, U> Mono<OutgoingResponse> createOutgoingResponse(
//      ZonedDateTime timestamp,
//      EntityId entityId,
//      P assembledData,
//      int eventNumber,
//      OutgoingResponseModel<P, U> model
//  ) {
//    OutgoingResponseCreator<U> creator = model.creatorType() != null ?
//        beanRegistry.getBean(model.creatorType()) : model.creator();
//    return Mono.deferContextual(ctx -> {
//      if (!hasRequestId(ctx)) {
//        return Mono.just(correlationId(ctx) + ": No incoming request in context, so skipping outgoing response "
//                + creator.getClass())
//            .doOnNext(System.out::println)
//            .then(Mono.empty());
//      } else {
//        HttpResponseMessage responseMessage = creator.create(
//            model.dataAdapter().apply(assembledData),
//            new OutgoingRequestCreator.Context() {
//
//              @Override
//              public EntityId entityId() {
//                return entityId;
//              }
//
//              @Override
//              public String correlationId() {
//                return Correlation.correlationId(ctx);
//              }
//
//              @Override
//              public ZonedDateTime timestamp() {
//                return timestamp;
//              }
//
//            }
//        );
//        return Mono.just(new OutgoingResponse(
//            eventNumber,
//            responseMessage,
//            requestId(ctx)
//        ));
//      }
//    });
//  }

//  private ZonedDateTime getDeadline(State targetState) {
//    return targetState.timeout().map(timeout -> ZonedDateTime.now(clock).plus(timeout.duration())).orElse(null);
//  }

  private Mono<ProcessResult> handleTransitionError(
      List<Change> changes,
      Throwable e//,
      //EventLog eventLog
  ) {
//    EntityId entityId = changes.getLast().entityId();
//    EntityModel entityModel = changes.getLast().entityModel();
    //Message.IncomingRequest incomingRequest = changes.getLast().incomingRequest();
    Supplier<Mono<ProcessResult>> handleRaceOrError = () -> {
      if (e instanceof ChangeRaced cr) {
        return withCorrelationId(correlationId -> listener.changeRaced(correlationId, toListenerFormat(changes), cr))
            .thenReturn(new Raced());
      } else {
        return withCorrelationId(correlationId -> listener.changeFailed(correlationId, toListenerFormat(changes), e))
            .thenReturn(new Failed(toString(e)));
      }
    };
//    if (e instanceof DuplicateMessage) {
//      return outgoingResponseByRequest.execute(incomingRequest.messageId(), incomingRequest.clientId())
//          .single()
//          .flatMap(originalRequest -> (Arrays.equals(originalRequest.requestDigest(), incomingRequest.digest())) ?
//              repeatedRequest(
//                  entityId,
//                  entityModel,
//                  incomingRequest.clientId(),
//                  incomingRequest.messageId(),
//                  originalRequest.responseMessage()
//              )
//              :
//              invalidRequest(
//                  incomingRequest.withMessageId("C" + incomingRequest.messageId()),
//                  eventLog,
//                  "Message identifier '" + incomingRequest.messageId() + "' not unique"
//              ))
//          .switchIfEmpty(handleRaceOrError.get());
//    } else {
    return handleRaceOrError.get();
//    }
  }

  private String toString(Throwable e) {
    StringWriter sw = new StringWriter();
    try (PrintWriter pw = new PrintWriter(sw)) {
      e.printStackTrace(pw);
      return sw.toString();
    }
  }

//  private TraversableState traverseTo(int eventNumber, EntityModel entityModel, List<Event<?>> eventLog) {
//    TraversableState state = begin(entityModel);
//    // Skip till eventNumber
//    for (var event : eventLog) {
//      if (event.eventNumber() <= eventNumber) {
//        state = state.forward(event.type());
//      }
//    }
//    return state;
//  }

//  private List<Event<?>> createScheduledEvents(List<EventType<Void, ?>> scheduledEventTypes, EventLog eventLog) {
//    return IntStream.range(0, scheduledEventTypes.size())
//        .mapToObj(n -> {
//          var eventType = scheduledEventTypes.get(n);
//          int eventNumber = eventLog.lastEventNumber() + n + 1;
//          return switch (eventType) {
//            case BasicEventType.Cancel t ->
//                new Event<>(eventNumber, t, clock, new BasicEventType.Rollback.Data(0, "Cancel"));
//            case BasicEventType.Rollback t -> new Event<>(
//                eventNumber,
//                t,
//                clock,
//                new BasicEventType.Rollback.Data(
//                    eventLog.events()
//                        .reversed()
//                        .stream()
//                        .filter(Event::isIncomingRequest)
//                        .findFirst()
//                        .map(e -> e.eventNumber() - 1)
//                        .orElseThrow(),
//                    "Scheduled rollback "
//                )
//            );
//            default -> new Event<>(eventNumber, eventType, clock);
//          };
//        })
//        .collect(toList());
//  }

//  private boolean isPendingIncomingResponse(EventLog ignoredEventLog) {
//    return false;
//    TransitionModel<?> lastTransition = lastTransition(eventLog);
//    return lastTransition != null && lastTransition.outgoingRequests()
//        .stream().anyMatch(s -> s.responseValidator() != null);
//  }

//  private TransitionModel<?> lastTransition(EventLog eventLog) {
//    var events = eventLog.events();
//    if (events.isEmpty()) return null;
//    var nextToLastState = eventLog.entityModel().begin().forward(events.subList(0, events.size()-1).stream().map(Event::getType).toList());
//    return requireNonNull(nextToLastState).transition(events.getLast().getType());
//  }

//  private TransitionModel<?, ?> transitionForEventNumber(EventLog eventLog, int eventNumber) {
//    Traverser traverser = traversers.get(eventLog.entityModel());
////    var currentState = traverser.currentState(eventLog);
//    var events = eventLog.events();
//    if (events.isEmpty()) {
//      throw new IllegalStateException("Event log is empty");
//    }

  /// /    TraversableState state = begin(eventLog.entityModel());
//    State state = eventLog.entityModel().initialState();
//    for (var event : events) {
//      if (event.eventNumber() == eventNumber) {
//        State workingState = state;
//        return ofNullable(traverser.accept(state, event.type()))
//            .orElseThrow(() -> new IllegalStateException(
//                "No transition for event " + event.typeName() + " on " + eventLog.entityModel().name() + " from "
//                    + workingState.name()));
//        //return state.transition(event.type(), true);
//      }
//      state = traverser.targetState(state, event.type());//state.forward(event.type());
//    }
//    throw new IllegalStateException("Event number " + eventNumber + " not found in event log");
//  }

//  private TraversableState traverseTo(EntityModel entityModel, List<Event<?>> eventLog, int eventNumber) {
//    if (eventNumber == 0)
//      return begin(entityModel);
//    var state = begin(entityModel);
//    for (var event : eventLog) {
//      state = state.forward(event.type());
//      if (event.eventNumber() == eventNumber)
//        return state;
//    }
//    throw new IllegalArgumentException("No event with number " + eventNumber);
//  }
  private EntityId newEntityId() {
    return new EntityId.UUID(UUID.randomUUID());
  }

  public Traverser traverser(EntityModel entityModel) {
    return traversers.get(entityModel);
  }

//  private TraversableState begin(EntityModel model) {
//    var r = begin.get(model);
//    if (r == null) throw new IllegalStateException("Traverser for " + model.name() + " (" + model.id() + ") not found");
//    return r;
//  }

}
