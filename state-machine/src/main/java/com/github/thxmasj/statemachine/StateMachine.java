package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.Correlation.correlationId;
import static com.github.thxmasj.statemachine.Correlation.hasRequestId;
import static com.github.thxmasj.statemachine.Correlation.hasResponseSink;
import static com.github.thxmasj.statemachine.Correlation.requestId;
import static com.github.thxmasj.statemachine.Correlation.responseSink;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.EventTrigger.EntitySelector;
import com.github.thxmasj.statemachine.IncomingRequestValidator.Context;
import com.github.thxmasj.statemachine.IncomingResponseValidator.Result;
import com.github.thxmasj.statemachine.Notification.OutgoingResponse;
import com.github.thxmasj.statemachine.OutboxWorker.ForwardStatus;
import com.github.thxmasj.statemachine.RequiredData.RequirementsNotFulfilled;
import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Status;
import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.DuplicateMessage;
import com.github.thxmasj.statemachine.database.EntityGroupNotInitialised;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.database.jdbc.JDBCClient;
import com.github.thxmasj.statemachine.database.mssql.ChangeState;
import com.github.thxmasj.statemachine.database.mssql.ChangeState.Change;
import com.github.thxmasj.statemachine.database.mssql.CreateSchema;
import com.github.thxmasj.statemachine.database.mssql.DequeueAndStoreReceipt;
import com.github.thxmasj.statemachine.database.mssql.EventsByEntityId;
import com.github.thxmasj.statemachine.database.mssql.EventsByLastEntity;
import com.github.thxmasj.statemachine.database.mssql.EventsByLookupId;
import com.github.thxmasj.statemachine.database.mssql.EventsByMessageId;
import com.github.thxmasj.statemachine.database.mssql.IncomingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.LastSecondaryId;
import com.github.thxmasj.statemachine.database.mssql.MoveToDLQ;
import com.github.thxmasj.statemachine.database.mssql.NextDeadline;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.OutgoingResponseAndRequestDigestByRequest;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.database.mssql.SecondaryIdByEntityId;
import com.github.thxmasj.statemachine.http.HttpClient;
import com.github.thxmasj.statemachine.http.RequestMapper;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.One;
import reactor.util.retry.RetrySpec;

public class StateMachine {

  private final RequestMapper requestMapper;
  private final Function<List<String>, Mono<Void>> delayer;
  private final BeanRegistry beanRegistry;
  private final Clock clock;
  private final Listener listener;
  private static final Random random = new Random();
  private static final Mono<Long> reattemptDelay = Mono.defer(() -> Mono.delay(Duration.ofMillis(100 + random.nextInt(1000))));
  private final boolean singleClientPerEntity;
  @SuppressWarnings("ALL")
  private final List<Looper<?>> workers = new ArrayList<>();
  private final ChangeState changeState;
  private final EventsByEntityId eventsByEntityId;
  private final EventsByLookupId eventsByLookupId;
  private final EventsByMessageId eventsByMessageId;
  private final EventsByLastEntity eventsByLastEntity;
  private final SecondaryIdByEntityId secondaryIdByEntityId;
  private final LastSecondaryId lastSecondaryId;
  private final DequeueAndStoreReceipt dequeueAndStoreReceipt;
  private final MoveToDLQ moveToDLQ;
  private final NextDeadline nextDeadline;
  private final OutgoingResponseAndRequestDigestByRequest outgoingResponseByRequest;
  private final IncomingRequestByEvent incomingRequestByEvent;
  private final OutgoingRequestByEvent outgoingRequestByEvent;
  private final Map<UUID, OutgoingRequestCreator<?>> outgoingRequestCreators;
  private final Function<OutboxQueue, HttpClient> clients;

  public StateMachine(
      RequestMapper requestMapper,
      Function<List<String>, Mono<Void>> delayer,
      BeanRegistry beanRegistry,
      List<EntityModel> entityModels,
      DataSource dataSource,
      DataSource schemaDataSource,
      String schemaName,
      String role,
      Clock clock,
      Listener listener,
      boolean singleClientPerEntity,
      Function<OutboxQueue, HttpClient> clients
  ) {
    this.outgoingRequestCreators =
        Stream.concat(
                entityModels.stream()
                    .flatMap(e -> e.transitions().stream())
                    .flatMap(t -> t.outgoingRequests().stream()),
                entityModels.stream()
                    .flatMap(e -> e.transitions().stream())
                    .filter(t -> t.reverse() != null)
                    .map(TransitionModel::reverse)
                    .flatMap(t -> t.outgoingRequests().stream())
            )
            .filter(r -> r.notificationCreator() != null)
            .map(OutgoingRequestModel::notificationCreator)
            .distinct()
            .collect(toMap(OutgoingRequestCreator::id, nc -> nc));
    this.requestMapper = requestMapper;
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    this.beanRegistry = beanRegistry;
    // Schema must be created before OutboxWorker/Resolver attempts to use it and before DatabaseAccess validates PKs
    if (schemaDataSource != null) {
      new CreateSchema(entityModels, schemaName, role).execute(new JDBCClient(schemaDataSource)).blockOptional(ofSeconds(10));
    }
    this.clock = clock;
    var jdbcClient = new JDBCClient(dataSource);
    this.changeState = new ChangeState(entityModels, jdbcClient, schemaName, clock);
    this.eventsByEntityId = new EventsByEntityId(dataSource, entityModels, schemaName, clock);
    this.eventsByLookupId = new EventsByLookupId(dataSource, entityModels, schemaName, clock);
    this.eventsByMessageId = new EventsByMessageId(dataSource, entityModels, schemaName, clock);
    this.eventsByLastEntity = new EventsByLastEntity(dataSource, entityModels, schemaName, clock);
    this.secondaryIdByEntityId = new SecondaryIdByEntityId(dataSource, entityModels, schemaName);
    this.lastSecondaryId = new LastSecondaryId(dataSource, entityModels, schemaName);
    this.dequeueAndStoreReceipt = new DequeueAndStoreReceipt(jdbcClient, schemaName, clock);
    this.moveToDLQ = new MoveToDLQ(jdbcClient, schemaName);
    this.nextDeadline = new NextDeadline(jdbcClient, clock, entityModels, schemaName);
    this.outgoingResponseByRequest = new OutgoingResponseAndRequestDigestByRequest(dataSource, schemaName);
    this.incomingRequestByEvent = new IncomingRequestByEvent(dataSource, entityModels, schemaName);
    this.outgoingRequestByEvent = new OutgoingRequestByEvent(dataSource, schemaName);
    this.listener = listener;
    this.singleClientPerEntity = singleClientPerEntity;
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
          case Empty -> Duration.ofSeconds(1);
          case Error -> Duration.ofSeconds(10);
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

  public Mono<ProcessResult> processRequest(String requestData) {
    try {
      HttpRequestMessage message;
      try {
        message = HttpMessageParser.parseRequest(requestData);
      } catch (Exception e) {
        return Mono.just(new ProcessResult(Status.Rejected, null, null, String.format("Failed to parse incoming message: %s", e.getMessage())));
      }
      IncomingRequestModelBuilder<?> requestModelBuilder;
      try {
        requestModelBuilder = requestMapper.incomingRequest(message);
      } catch (Exception e) {
        return Mono.just(new ProcessResult(Status.Rejected, null, null, String.format("Failed to map incoming request: %s\n%s", e, message.message())));
      }
      if (requestModelBuilder == null) {
        return Mono.just(new ProcessResult(Status.Rejected, null, null, String.format("No mapping for incoming message: %s", message.requestLine())));
      }
      IncomingRequestModel requestModel;
      try {
        requestModel = requestModelBuilder.build();
      } catch (Exception e) {
        return Mono.just(new ProcessResult(Status.Rejected, null, null, String.format("Failed to build request model for incoming message: %s\n%s", e, message.requestLine())));
      }
      var incomingRequest = new IncomingRequest(
          UUID.randomUUID(),
          requestModel.eventTrigger().build(), // TODO: Errors end up in the big try/catch. Related: https://github.com/orgs/the-backend-project/projects/1/views/1?pane=issue&itemId=79673689
          requestModel.clientId(),
          requestModel.derivedMessageId() ? (entityId, eventType) -> entityId.value() + "-" + eventType.id() : (_, _) -> requestModel.messageId(),
          requestModel.digest() != null ? requestModel.digest() : MessageDigest.getInstance("SHA256").digest(message.message().getBytes(StandardCharsets.UTF_8)),
          message
      );
      IncomingRequestValidator<?> validator = requestModel.validatorClass() != null ? beanRegistry.getBean(requestModel.validatorClass()) : requestModel.validator();
      String correlationId = requireNonNullElse(requestModel.correlationId(), requireNonNullElse(message.headerValue("X-Correlation-Id"), "processRequest"));
      Sinks.One<HttpResponseMessage> responseSink = Sinks.one();
      return (switch (incomingRequest.eventTrigger().eventType()) {
        case EventType type when type.isCancel() || type.isRollback() -> doProcessRollback(1, incomingRequest);
        case EventType _ -> doProcessIncomingRequest(1, incomingRequest, validator);
      })
          .flatMap(processResult -> processResult.responseMessage() != null ?
              Mono.just(processResult) :
              responseSink.asMono()
                  .map(responseMessage -> new ProcessResult(processResult.status(), processResult.entity(), responseMessage, processResult.error()))
                  .timeout(ofSeconds(20))
              )
          .contextWrite(Correlation.contextOf(correlationId, responseSink, incomingRequest.id()));
    } catch (Exception e) {
      listener.clientRequestFailed("N/A", null, null, e);
      return Mono.just(new ProcessResult(Status.Failed, null, null, "Request failed: " + e));
    }
  }

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
            // The state has already been resolved by another resolver or incoming request
            return Mono.just(ResolverStatus.Ok);
          }
          var currentState = begin(deadline.entityModel()).forward(eventLog.events().stream().map(Event::type).toList());
          if (currentState == null)
            return Mono.error(new RuntimeException("Invalid event log: " + eventLog.events().stream().map(Event::typeName).collect(joining(","))));
          EventType eventType = currentState.state().timeout().get().eventType();
          return processEvents(
              eventLog,
              List.of(),
              new InputEvent<Void>(eventType, null, null),
              null,
              List.of()
          ).flatMap(processResult -> switch (processResult.status()) {
                // State is resolved and deadline already deleted by the change triggered by this event or the racing one.
                case Accepted, Raced -> Mono.just(ResolverStatus.Ok);
                // This is a bug.
                // - Rejection should not happen unless model is wrong. TODO: sanitize
                // - Repeats and conflicts should only happen with incoming requests (which this is not).
                case Rejected, Repeated, Conflicted -> Mono.error(new IllegalStateException("Unexpected result for state resolving: " + processResult.status()));
                // Need to retry. Deadline was already modified when reading.
                case Failed -> {
                  listener.resolveStateFailed(
                      deadline.correlationId(),
                      eventLog.entityId(),
                      currentState.state().name(),
                      eventType,
                      processResult.error()
                  );
                  yield backoff.isExhausted(eventLog.events().getLast().timestamp(), deadline.nextAttemptAt(), clock) ?
                      Mono.error(new RuntimeException("Period for state resolving exhausted: " + Duration.between(
                          eventLog.events()
                              .getLast()
                              .timestamp(), ZonedDateTime.now(clock)
                      ))) :
                      Mono.just(ResolverStatus.Ok);
                }
              }
          ).contextWrite(Correlation.contextOf(deadline.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(listener::processNextDeadlineFailed)
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

  private Mono<ProcessResult> invalidRequest(
      String messageId,
      IncomingRequest incomingRequest,
      EventLog eventLog,
      String reason
  ) {
    return invalidRequest(
        incomingRequestNotification(eventLog.lastEventNumber() + 1, incomingRequest, messageId),
        eventLog,
        reason
    );
  }

  private Mono<ProcessResult> invalidRequest(
      Notification.IncomingRequest incomingRequest,
      EventLog eventLog,
      String reason
  ) {
    return processEventForInvalidOrRejectedRequest(
        BuiltinEventTypes.InvalidRequest,
        incomingRequest,
        eventLog,
        reason
    );
  }

  private Mono<ProcessResult> rejectedRequest(
      String messageId,
      IncomingRequest incomingRequest,
      EventLog eventLog,
      String reason
  ) {
    return processEventForInvalidOrRejectedRequest(
        BuiltinEventTypes.RejectedRequest,
        incomingRequestNotification(eventLog.lastEventNumber() + 1, incomingRequest, messageId),
        eventLog,
        reason
    );
  }

  private Mono<ProcessResult> processEventForInvalidOrRejectedRequest(
      EventType eventType,
      Notification.IncomingRequest incomingRequest,
      EventLog eventLog,
      String reason
  ) {
    return processEvents(
        eventLog,
        List.of(),
        new InputEvent<>(eventType, null, reason),
        incomingRequest,
        List.of()
    );
  }

  private Mono<ProcessResult> repeatedRequest(EntityId entityId, EntityModel entityModel, String clientId, String messageId, HttpResponseMessage responseMessage) {
    return correlationId()
        .doOnNext(correlationId -> listener.repeatedRequest(correlationId, entityId, clientId, messageId))
        .map(_ -> new ProcessResult(Status.Repeated, new Entity(entityId, List.of(), entityModel), responseMessage, null));
  }

  private Mono<EventLog> fetchEventLogByEntitySelector(String clientId, EntityModel entityModel, EntitySelector entitySelector) {
    return switch (entitySelector) {
      case EntitySelector s when s.entityId() != null -> eventsByEntityId.execute(entityModel, entitySelector.entityId());
      case EntitySelector s when s.secondaryId() != null -> eventsByLookupId.execute(entityModel, s.secondaryId());
      case EntitySelector s when s.messageId() != null -> eventsByMessageId.execute(entityModel, s.messageId(), clientId);
      case EntitySelector s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private <T> Mono<ProcessResult> doProcessIncomingRequest(
      int attempt,
      IncomingRequest incomingRequest,
      IncomingRequestValidator<T> validator
  ) {
    EventTrigger eventTrigger = incomingRequest.eventTrigger();
    return (eventTrigger.createEntity() ? Mono.just(emptyEventLog(eventTrigger.entityModel())) :
        fetchEventLogByEntitySelector(incomingRequest.clientId(), eventTrigger.entityModel(), eventTrigger.entitySelectors().getFirst())
            .onErrorResume(
                UnknownEntity.class,
                e -> {
                  var mainEntitySelector = eventTrigger.entitySelectors().getFirst();
                  if (mainEntitySelector.secondaryId() != null && isInitialInGroup(mainEntitySelector.secondaryId())) {
                    // Will trigger creation of secondary id
                    return Mono.just(emptyEventLog(eventTrigger.entityModel()));
                  } else if (mainEntitySelector.entityId() != null && mainEntitySelector.createIfNotExists()) {
                    return Mono.just(emptyEventLog(eventTrigger.entityModel(), mainEntitySelector.entityId()));
                  } else {
                    return Mono.error(e);
                  }
                }
            )
    )
        .flatMap(eventLog -> {
          EntityId entityId = eventLog.entityId();
          List<Event> events = eventLog.events();
          var currentState = begin(eventTrigger.entityModel()).forward(eventLog.effectiveEvents().stream().map(Event::type).toList());
          List<Event> scheduledEvents = scheduledEvents(eventTrigger.entityModel(), eventLog);
          int nextEventNumber = eventLog.lastEventNumber() + scheduledEvents.size() + 1;
          String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventType());
          if (eventTrigger.eventType() != BuiltinEventTypes.Status && singleClientPerEntity && events.stream()
              .anyMatch(e -> e.isIncomingRequest() && !Objects.equals(e.clientId(), incomingRequest.clientId()))) {
            return invalidRequest(
                messageId,
                incomingRequest,
                eventLog,
                "Client not allowed"
            );
          }
          Notification requestNotification = incomingRequestNotification(nextEventNumber, incomingRequest, messageId);
          var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream().map(Event::type).toList());
          if (stateAfterScheduledEvents == null) {
            // TODO: Handle better. InconsistentState?
            throw new RuntimeException("Can't apply scheduled events " +
                scheduledEvents.stream().map(Event::typeName).collect(joining(",")) +
                " to state " + currentState.state());
          }
          // TODO: Need to check that state accepts the event for the request before validation, otherwise
          //       validation can fail with RequirementsNotFulfilled. This duplicates the behavior, though
          //       (see below, rejectIfNotRepeat called twice).
          if (stateAfterScheduledEvents.forward(eventTrigger.eventType()) == null) {
            return rejectIfNotRepeat(entityId, eventLog, messageId, incomingRequest, "State " + eventTrigger.entityModel().name() + "/" + stateAfterScheduledEvents.state() + " does not accept " + eventTrigger.eventType() + " (" + eventTrigger.eventType().id() + ")");
          }
          return validateRequest(
              eventLog,
              messageId,
              incomingRequest.clientId(),
              incomingRequest.requestMessage(),
              validator,
              nextEventNumber,
              eventTrigger.eventType(),
              requestNotification,
              scheduledEvents
          )
              .flatMap(inputEvent -> processEvents(
                  eventLog,
                  scheduledEvents,
                  inputEvent,
                  requestNotification,
                  eventLog.events().isEmpty() && !eventTrigger.entitySelectors().isEmpty() && eventTrigger.entitySelectors().getFirst().secondaryId() != null ?
                      List.of(eventTrigger.entitySelectors().getFirst().secondaryId()) :
                      List.of()
              ))
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
                  reattemptDelay.then(doProcessIncomingRequest(attempt + 1, incomingRequest, validator))
              );
        })
        .onErrorResume(UnknownEntity.class, e -> {
          EntityId entityId = e.id() != null ? e.id() : newEntityId();
          String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventType());
          return processEvents(
              emptyEventLog(eventTrigger.entityModel(), entityId),
              List.of(),
              new InputEvent<>(BuiltinEventTypes.UnknownEntity, null, null),
              incomingRequestNotification(1, incomingRequest, messageId),
              List.of()
          );
        })
        .onErrorResume(t -> withCorrelationId(correlationId -> listener.clientRequestFailed(
                correlationId,
                eventTrigger.entitySelectors().isEmpty() ? null : eventTrigger.entitySelectors().getFirst().entityId(),
                new Event(-1, eventTrigger.eventType(), clock),
                t
            ))
                .then(Mono.error(t))
        );
  }

  private Notification.IncomingRequest incomingRequestNotification(
      int eventNumber,
      IncomingRequest incomingRequest,
      String messageId
  ) {
    return new Notification.IncomingRequest(
        incomingRequest.id(),
        eventNumber,
        new HttpRequestMessage(
            incomingRequest.requestMessage().method(),
            incomingRequest.requestMessage().uri(),
            stripAuthorizationHeader(incomingRequest.requestMessage().headers()),
            incomingRequest.requestMessage().body()
        ),
        messageId,
        incomingRequest.clientId(),
        incomingRequest.digest()
    );
  }

  private Notification.IncomingRequest incomingRequestNotification(
      Notification.IncomingRequest incomingRequest,
      String messageId
  ) {
    return new Notification.IncomingRequest(
        incomingRequest.id(),
        incomingRequest.eventNumber(),
        incomingRequest.message(),
        messageId,
        incomingRequest.clientId(),
        incomingRequest.digest()
    );
  }

  private Map<String, String> stripAuthorizationHeader(Map<String, String> headers) {
    return headers.entrySet().stream()
        .filter(e -> !e.getKey().equalsIgnoreCase("Authorization"))
        .collect(toMap(Entry::getKey, Entry::getValue));
  }

  private Mono<ProcessResult> rejectIfNotRepeat(
      EntityId entityId,
      EventLog eventLog,
      String messageId,
      IncomingRequest incomingRequest,
      String errorMessage
  ) {
    return outgoingResponseByRequest.execute(messageId, incomingRequest.clientId())
        .flatMap(inboxEntry -> Arrays.equals(inboxEntry.requestDigest(), incomingRequest.digest()) ?
            repeatedRequest(
                entityId,
                eventLog.entityModel(),
                incomingRequest.clientId(),
                messageId,
                inboxEntry.responseMessage()
            ) :
            invalidRequest(
                messageId,
                incomingRequest,
                eventLog,
                "Message identifier not unique"
            )
        )
        // Request is not a repeat, so reject it.
        .switchIfEmpty(rejectedRequest(
            'T' + messageId, // don't include temporary rejected incoming requests in repeat check
            incomingRequest,
            eventLog,
            errorMessage
        ));
  }

  private <T> Mono<InputEvent<T>> validateRequest(
      EventLog eventLog,
      String messageId,
      String clientId,
      HttpRequestMessage requestMessage,
      IncomingRequestValidator<T> validator,
      int currentEventNumber,
      EventType validEventType,
      Notification requestNotification,
      List<Event> scheduledEvents
  ) {
    RequiredData requiredData = requiredData(
        validator,
        new Entity(eventLog.entityId(), eventLog.secondaryIds(), eventLog.entityModel()),
        eventLog.effectiveEvents(),
        scheduledEvents,
        requestNotification
    );
    var incomingRequest = new Input.IncomingRequest(
        requestMessage,
        messageId,
        clientId,
        currentEventNumber
    );
    return validator.execute(
        eventLog.entityId(),
        new IncomingRequestContext<>(validEventType),
        incomingRequest,
        requiredData
    );
  }

  private Mono<IncomingResponseValidator.Result> validateResponse(
      HttpRequestMessage requestMessage,
      HttpResponseMessage responseMessage,
      IncomingResponseValidator<?> validator,
      EntityId entityId,
      int currentEventNumber,
      RequiredData requiredData
  ) {
    return validator.execute(
        entityId,
        new IncomingResponseContext<>(
        ),
        requestMessage,
        new Input.IncomingResponse(
            responseMessage,
            currentEventNumber
        ),
        requiredData
    );
  }

  private static class IncomingRequestContext<DATA_TYPE> implements Context<DATA_TYPE> {

    private final EventType validRequestEventType;

    private IncomingRequestContext(
        EventType validRequestEventType
    ) {
      this.validRequestEventType = validRequestEventType;
    }

    @Override
    public InputEvent<DATA_TYPE> invalidRequest(String errorMessage) {
      return new InputEvent<>(BuiltinEventTypes.InvalidRequest, null, errorMessage);
    }

    @Override
    public InputEvent<DATA_TYPE> invalidRequest(EventType eventType, DATA_TYPE data, String errorMessage) {
      return new InputEvent<>(eventType, data, errorMessage);
    }

    @Override
    public InputEvent<DATA_TYPE> invalidRequest(EventType eventType, DATA_TYPE data) {
      return new InputEvent<>(eventType, data, null);
    }

    @Override
    public InputEvent<DATA_TYPE> validRequest(DATA_TYPE data) {
      return new InputEvent<>(validRequestEventType, data, null);
    }

    @Override
    public InputEvent<DATA_TYPE> validRequest() {
      return new InputEvent<>(validRequestEventType, null, null);
    }

  }

  private static class IncomingResponseContext<DATA_TYPE> implements IncomingResponseValidator.Context<DATA_TYPE> {

    @Override
    public InputEvent<DATA_TYPE> requestUndelivered(String cause) {
      return new InputEvent<>(BuiltinEventTypes.RequestUndelivered, null, cause);
    }

    @Override
    public InputEvent<DATA_TYPE> validResponse(EventType eventType, DATA_TYPE data) {
      return new InputEvent<>(eventType, data, null);
    }

    @Override
    public InputEvent<DATA_TYPE> invalidResponse(String cause) {
      return new InputEvent<>(BuiltinEventTypes.InvalidResponse, null, cause);
    }

    @Override
    public InputEvent<DATA_TYPE> rollback(String cause) {
      return new InputEvent<>(Rollback, null, cause);
    }
  }

  private RequiredData requiredData(
      Object dataRequirer,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Notification incomingNotification
  ) {
    return new RequiredData(
        entity,
        join(eventLog, newEvents),
        List.of(),
        List.of(),
        incomingNotification != null ? List.of(incomingNotification) : List.of(),
        dataRequirer instanceof DataRequirer dr ? dr.requirements() : Requirements.none(),
        dataRequirer.getClass(),
        incomingRequestByEvent,
        outgoingRequestByEvent
    );
  }

  private record IncomingResponseStatus(
      Notification.IncomingResponse response,
      ProcessResult processResult,
      IncomingResponseValidator.Result validationResult
  ) {}

  private Mono<ProcessResult> doProcessRollback(
      int attempt,
      IncomingRequest incomingRequest
  ) {
    EventTrigger eventTrigger = incomingRequest.eventTrigger();
    EntityModel entityModel = eventTrigger.entityModel();
    return fetchEventLogByEntitySelector(
        incomingRequest.clientId(),
        eventTrigger.entityModel(),
        eventTrigger.entitySelectors().getFirst()
    )
        .onErrorResume(
            UnknownEntity.class,
            e -> {
              var mainEntitySelector = eventTrigger.entitySelectors().getFirst();
              if (mainEntitySelector.secondaryId() != null && isInitialInGroup(mainEntitySelector.secondaryId())) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel()));
              } else if (mainEntitySelector.entityId() != null && mainEntitySelector.createIfNotExists()) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel(), mainEntitySelector.entityId()));
              } else if (mainEntitySelector.messageId() != null && mainEntitySelector.createIfNotExists()) {
                return Mono.just(emptyEventLog(eventTrigger.entityModel(), newEntityId()));
              } else {
                return Mono.error(e);
              }
            }
        )
        .flatMap(eventLog -> {
          int rollbackToEventNumber = eventTrigger.entitySelectors().getFirst().messageId() != null ?
              eventLog.effectiveEvents()
                  .stream()
                  .filter(e -> eventTrigger.entitySelectors().getFirst().messageId().equals(e.messageId()))
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
          //String messageId = (rollbackToEventNumber < lastEventNumber ? "R" : "") + incomingRequest.messageId().apply(eventLog.entityId(), incomingRequest.eventTrigger().eventType());
          String messageId = (rollbackToEventNumber < lastEventNumber ? "R" : "") +
              ofNullable(eventTrigger.entitySelectors().getFirst().messageId())
                  .orElse(incomingRequest.messageId().apply(eventLog.entityId(), eventTrigger.eventType()));
          // Rollback of a request which is not the most recent is not allowed
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
          Notification incomingNotification = incomingRequestNotification(lastEventNumber + 1, incomingRequest, messageId);
          return processEvents(eventLog, List.of(), new InputEvent<>(incomingRequest.eventTrigger().eventType(), rollbackToEventNumber + 1, null), incomingNotification, List.of())
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
                  doProcessRollback(attempt + 1, incomingRequest)
              ));
        })
        .onErrorResume(t -> withCorrelationId(correlationId -> listener.rollbackFailed(correlationId, null, t)).then(Mono.error(t)));
  }

  private boolean isUnrepeatable(int attempt, ProcessResult.Status processStatus, boolean stateIsPendingChange) {
    // A rejected event might be repeatable if current state is pending change shortly.
    // Using 100 reattempts, then, as we delay for 100 ms to cover changes arriving within 10 seconds (which should
    // cover synchronous notification exchanges). TODO: Use a backoff algorithm instead in that case
    return
        !(processStatus == Status.Rejected && attempt < 100 && stateIsPendingChange) &&
        !(processStatus == Status.Raced && attempt < 3);
  }

  private Mono<Void> withCorrelationId(Consumer<String> consumer) {
    return correlationId().doOnNext(consumer).then();
  }

  public record ProcessResult(
      Status status,
      Entity entity,
      HttpResponseMessage responseMessage,
      String error
  ) {

    public record Entity(
       EntityId id,
       List<SecondaryId> secondaryIds,
       EntityModel model
    ) {}

    public enum Status {
      Accepted, // Event is accepted successfully (and stored)
      Repeated, // Event is a repeat of a previously accepted event.
      Rejected, // Event is rejected (not allowed for the current state)
      Conflicted, // Event conflicts with a previous event (message id was reused)
      Failed, // Event processing failed (temporarily, can try again)
      Raced // Storing was raced by another event (can try again)
    }

    boolean isAccepted() {
      return status == Status.Accepted;
    }

  }

  private <I, O> Flux<ChangeResult> calculateNestedChanges(Entity entity, TransitionWithData<I, O> transitionWithData) {
    return Flux.fromIterable(transitionWithData.transition().model().eventTriggers())
        .flatMap(eventToProcess -> calculateNestedChange(entity, eventToProcess.apply(transitionWithData.data()).build()));
  }

  private Mono<ChangeResult> calculateNestedChange(Entity rootEntity, EventTrigger nestedTransition) {
    return calculateNestedChange(rootEntity, nestedTransition.entitySelectors(), nestedTransition.entityModel(), nestedTransition.eventType(), nestedTransition.data());
  }

  private Mono<SecondaryId> next(Entity entity, SecondaryIdModel idModel) {
    if (entity.secondaryIds().isEmpty()) {
      return secondaryIdByEntityId.execute(entity.model(), idModel, entity.id());
    } else {
      SecondaryId secondaryId = entity.secondaryIds().stream()
          .filter(ids -> ids.model() == idModel)
          .findFirst()
          .orElseThrow(() -> new RuntimeException("No secondary id of type " + idModel + " found on entity " + entity));
      return Mono.just(secondaryId.model().group().next(secondaryId));
    }
  }

  private Mono<EntitySelector> next(Entity entity, EntitySelector selector) {
    // TODO: EntitySelector should be better typed
    return next(entity, selector.secondaryIdModel()).map(nextId -> new EntitySelector(
        selector.entityId(),
        selector.group(),
        nextId,
        selector.secondaryIdModel(),
        selector.messageId(),
        selector.last(),
        selector.create(),
        selector.createIfNotExists(),
        selector.fallback(),
        selector.next()
    ));
  }

  private Mono<EventLog> fetchEventLog(Entity rootEntity, EntityModel entityModel, EntitySelector mainSelector, List<EntitySelector> allSelectors) {
    return switch (mainSelector) {
      case EntitySelector s when s.entityId() != null -> eventsByEntityId.execute(entityModel, s.entityId());
      case EntitySelector s when s.secondaryId() != null && s.create() -> calculateNewIds(rootEntity, allSelectors).map(newIds -> new EventLog(entityModel, newEntityId(), newIds, List.of()));
      case EntitySelector s when s.secondaryId() != null -> eventsByLookupId.execute(entityModel, s.secondaryId());
      case EntitySelector s when s.last() != 0 -> eventsByLastEntity.execute(entityModel, s.secondaryIdModel(), s.group(), s.last());
      case EntitySelector s -> throw new IllegalStateException("Unexpected value: " + s);
    };
  }

  private Mono<ChangeResult> calculateNestedChange(
      Entity rootEntity,
      List<EntitySelector> rawSelectors,
      EntityModel entityModel,
      EventType eventType,
      Object eventData
  ) {
    return (rawSelectors.getFirst().next() ? next(rootEntity, rawSelectors.getFirst()).mergeWith(Flux.fromIterable(rawSelectors.subList(1, rawSelectors.size()))) : Flux.fromIterable(rawSelectors)).collectList()
        .flatMap(selectors -> fetchEventLog(rootEntity, entityModel, selectors.getFirst(), selectors)
            .flatMap(eventLog -> {
              var currentState = begin(entityModel)
                  .forward(eventLog.effectiveEvents().stream().map(Event::type).toList());
              List<Event> scheduledEvents = scheduledEvents(entityModel, eventLog);
              var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream()
                  .map(Event::type)
                  .toList());
              if (stateAfterScheduledEvents == null) {
                // TODO: Handle better. InconsistentState?
                return Mono.error(new RuntimeException("Can't apply scheduled events " +
                    scheduledEvents.stream().map(Event::typeName).collect(joining(",")) +
                    " to state " + currentState.state()));
              }
              return calculateChange(
                  eventLog,
                  scheduledEvents,
                  new InputEvent<>(eventType, eventData, null),
                  null,
                  eventLog.events().isEmpty() ? eventLog.secondaryIds() : List.of()
              )
                  .flatMap(result -> {
                        if (result.result().status() == Status.Rejected && rawSelectors.getFirst().fallback() != null) {
                          return calculateNestedChange(
                              rootEntity,
                              join(rawSelectors.getFirst().fallback(), rawSelectors.subList(1, rawSelectors.size())),
                              entityModel,
                              eventType,
                              eventData
                          );
                        } else {
                          return Mono.just(result);
                        }
                      }
                  );
            })
            .onErrorResume(EntityGroupNotInitialised.class, e -> {
                  if (eventType == BuiltinEventTypes.Status) {
                    return Mono.just(new ChangeResult(new ProcessResult(Status.Accepted, null, null, null), List.of()));
                  } else if (selectors.getFirst().createIfNotExists()) {
                    return calculateNewIds(rootEntity, selectors)
                        .flatMap(newIds -> calculateNestedChangeForNewEntity(
                            entityModel,
                            eventType,
                            eventData,
                            newIds
                        ));
                  } else {
                    return Mono.error(e);
                  }
                }
            )
            .onErrorResume(
                UnknownEntity.class,
                e -> {
                  if (selectors.getFirst().fallback() != null) {
                    return calculateNestedChange(
                        rootEntity,
                        join(selectors.getFirst().fallback(), selectors.subList(1, selectors.size())),
                        entityModel,
                        eventType,
                        eventData
                    );
                  } else if ((e.secondaryId() != null && isInitialInGroup(e.secondaryId())) || selectors.getFirst().createIfNotExists()) {
                    return calculateNewIds(rootEntity, selectors.subList(1, selectors.size()))
                        .flatMap(newIds -> calculateNestedChangeForNewEntity(
                            entityModel,
                            eventType,
                            eventData,
                            e.secondaryId() != null ? join(e.secondaryId(), newIds) : newIds
                        ));
                  } else {
                    return Mono.error(e);
                  }
                }
            )
        );
  }

  private Mono<List<SecondaryId>> calculateNewIds(Entity rootEntity, List<EntitySelector> entitySelectors) {
    return Flux.fromIterable(entitySelectors).flatMap(selector -> calculateNewId(rootEntity, selector)).collectList();
  }

  private Mono<SecondaryId> calculateNewId(Entity rootEntity, EntitySelector selector) {
    if (selector.secondaryId() != null) {
      if (selector.secondaryId().model().isSerial()) {
        return Mono.just(new SecondaryId(selector.secondaryId().model(), selector.secondaryId().data(), 1));
      } else {
        return Mono.just(selector.secondaryId());
      }
    } else if (selector.last() != 0) {
      return lastSecondaryId.execute(selector.secondaryIdModel(), selector.group())
          .switchIfEmpty(Mono.just(selector.secondaryIdModel().group().initial(selector.group())));
    } else if (selector.next()) {
      return next(rootEntity, selector.secondaryIdModel());
    } else {
      return Mono.error(new IllegalStateException("Unhandled entity selector (improve typing): " + selector));
    }
  }

  private boolean isInitialInGroup(SecondaryId id) {
    return id.model().group() != null && id.model().group().isInitial(id.data());
  }

  private Mono<ChangeResult> calculateNestedChangeForNewEntity(
      EntityModel entityModel,
      EventType eventType,
      Object eventData,
      List<SecondaryId> idsForNewEntity
  ) {
    return calculateChange(
        emptyEventLog(entityModel),
        List.of(),
        new InputEvent<>(eventType, eventData, null),
        null,
        idsForNewEntity
    );
  }

  private List<Event> scheduledEvents(EntityModel entityType, EventLog eventLog) {
    return begin(entityType).forward(eventLog.effectiveEvents().stream().map(Event::type).toList()).state().timeout()
        .filter(timeout -> ZonedDateTime.now(clock).isAfter(eventLog.effectiveEvents().getLast().timestamp().plus(timeout.duration())))
        .map(timeout -> List.of(new Event(eventLog.lastEventNumber() + 1, timeout.eventType(), clock)))
        .orElseGet(() -> {
          List<EventType> scheduledEventTypes = scheduledEvents(new Entity(eventLog.entityId(), eventLog.secondaryIds(), entityType), eventLog.effectiveEvents(), ZonedDateTime.now(clock))
              .stream()
              .toList();
          return IntStream
              .range(0, scheduledEventTypes.size())
              .mapToObj(i -> new Event(eventLog.lastEventNumber() + i + 1, scheduledEventTypes.get(i), clock))
              .toList();
        });
  }

  private record ChangeResult(ProcessResult result, List<Change> changes) {}

  private <I, O> Mono<ChangeResult> calculateChange(
      EventLog eventLog,
      List<Event> scheduledEvents,
      InputEvent<I> inputEvent,
      Notification incomingNotification,
      List<SecondaryId> idsForNewEntity
  ) {
    List<Event> effectiveEventLog = eventLog.effectiveEvents();
    EntityId entityId = eventLog.entityId();
    List<SecondaryId> secondaryIds = eventLog.secondaryIds();
    Entity entity = new Entity(
        entityId,
        join(secondaryIds, idsForNewEntity),
        eventLog.entityModel()
    );
    var currentState = begin(eventLog.entityModel()).forward(effectiveEventLog.stream().map(Event::type).toList());
    TransitionModel<I, O> transitionModelForInput = (TransitionModel<I, O>)
        currentState.forward(scheduledEvents.stream().map(Event::type).toList()).transition(inputEvent.eventType());
    if (transitionModelForInput == null) {
      return Mono.just(new ChangeResult(
          new ProcessResult(
              Status.Rejected,
              new Entity(entityId, List.of(), eventLog.entityModel()),
              null,
              "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:" + currentState.state()
                  + " does not accept " + Stream.concat(scheduledEvents.stream().map(Event::type), Stream.of(inputEvent.eventType()))
                  .map(et -> et.name() + " (" + et.id() + ")")
                  .toList()
          ), null
      ));
    }
    return createData(
        transitionModelForInput,
        entity,
        eventLog.effectiveEvents(),
        scheduledEvents,
        null,
        inputEvent,
        incomingNotification
    ).map(processingData -> new TransitionWithData<>(
            new ActualTransition<>(
                transitionModelForInput,
                new Event(
                    eventLog.lastEventNumber() + scheduledEvents.size() + 1,
                    inputEvent.eventType(),
                    clock,
                    incomingNotification instanceof Notification.IncomingRequest ir ? ir.messageId() : null,
                    incomingNotification instanceof Notification.IncomingRequest ir ? ir.clientId() : null,
                    inputEvent.data()
                )
            ), processingData
        ))
        .defaultIfEmpty(new TransitionWithData<>(
            new ActualTransition<>(
                transitionModelForInput, new Event(
                eventLog.lastEventNumber() + scheduledEvents.size() + 1,
                inputEvent.eventType(),
                clock,
                incomingNotification instanceof Notification.IncomingRequest ir ? ir.messageId() : null,
                incomingNotification instanceof Notification.IncomingRequest ir ? ir.clientId() : null,
                inputEvent.data()
            )
            ), null
        ))
        .flatMap(newTransitionWithData -> {
          for (var f : newTransitionWithData.transition().model().filters()) {
            if (!f.filter().test(newTransitionWithData.data())) {
              return calculateChange(
                  eventLog,
                  scheduledEvents,
                  f.alternative().apply(newTransitionWithData.data()),
                  incomingNotification,
                  idsForNewEntity
              );
            }
          }
          List<Event> newEvents = join(scheduledEvents, newTransitionWithData.transition().event());
          List<EventType> newEventTypes = newEvents.stream().map(Event::type).toList();
          if (currentState.forward(newEventTypes) == null) {
            String errorMessage = "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:"
                + currentState.state()
                + " does not accept " + newEventTypes.stream().map(e -> e.name() + " (" + e.id() + ")").toList();
            return Mono.just(new ChangeResult(
                new ProcessResult(
                    Status.Rejected,
                    new Entity(entityId, List.of(), eventLog.entityModel()),
                    null,
                    errorMessage
                ), null
            ));
          }
          List<Event> transitionEvents;
          List<Event> eventsToStore;
          TraversableState targetState;
          int startEventNumber;
          boolean reverseTransitions;
          if (inputEvent.eventType().isRollback()) {
            int numberOfEventToRollback;
            if (inputEvent.data() == null) {
              // No reference to an event to roll back to so roll back to previous state
              numberOfEventToRollback = eventLog.lastEventNumber();
              for (Event event : effectiveEventLog.reversed()) {
                numberOfEventToRollback = event.eventNumber();
                break;
              }
            } else {
              numberOfEventToRollback = Integer.parseInt(inputEvent.data().toString());
            }
            if (numberOfEventToRollback > 0) {
              startEventNumber = numberOfEventToRollback - 1;
              targetState = traverseTo(eventLog.entityModel(), eventLog.events(), numberOfEventToRollback - 1);
              transitionEvents = Event.join(
                  eventLog.events().subList(numberOfEventToRollback - 1, eventLog.events().size()),
                  scheduledEvents
              );
              reverseTransitions = true;
              eventsToStore = newEvents;
            } else {
              // Rollback arrived before incoming request
              startEventNumber = eventLog.lastEventNumber();
              targetState = currentState;
              transitionEvents = scheduledEvents;
              reverseTransitions = false;
              eventsToStore = newEvents;
            }
          } else if (inputEvent.eventType().isCancel()) {
            startEventNumber = 0;
            targetState = begin(eventLog.entityModel());
            transitionEvents = Event.join(effectiveEventLog, scheduledEvents);
            reverseTransitions = true;
            eventsToStore = newEvents;
          } else {
            startEventNumber = eventLog.lastEventNumber();
            targetState = currentState.forward(newEvents.stream().map(Event::type).toList());
            if (targetState == null) {
              return Mono.just(new ChangeResult(new ProcessResult(Status.Rejected, entity, null, null), null));
            }
            transitionEvents = scheduledEvents;
            reverseTransitions = false;
            eventsToStore = newEvents;
          }
          // TODO: Nested changes are calculated _after_ this, so createData does not have access to "nested entities".
          return Flux.concat(
                  transitionsWithData(
                      inputEvent,
                      actualTransitions(
                          startEventNumber,
                          entity.model(),
                          effectiveEventLog,
                          newEvents,
                          transitionEvents,
                          reverseTransitions
                      ),
                      entity,
                      effectiveEventLog,
                      scheduledEvents,
                      incomingNotification
                  ), Flux.just(newTransitionWithData)
              ).collectList()
              .flatMap(transitionsWithData -> Flux.fromIterable(transitionsWithData)
                  .flatMap(actualTransition -> calculateNestedChanges(entity, actualTransition))
                  .collectList()
                  .flatMap(changeResultList -> {
                    ProcessResult negativeResult = changeResultList.stream()
                        .map(ChangeResult::result)
                        .filter(result -> !result.isAccepted())
                        .findFirst()
                        .orElse(null);
                    if (negativeResult != null)
                      return Mono.just(new ChangeResult(
                          new ProcessResult(
                              negativeResult.status(),
                              entity,
                              negativeResult.responseMessage(),
                              negativeResult.error()
                          ), null
                      ));
                    List<ProcessResult> processResults = changeResultList.stream()
                        .map(ChangeResult::result)
                        .toList();
                    List<Event> processedEvents = changeResultList.stream()
                        .flatMap(changeResult -> changeResult.changes().stream())
                        .flatMap(change -> change.newEvents().stream())
                        .toList();
                    List<Event> finalEventsToStore = addTransitionData(
                        eventsToStore,
                        transitionsWithData.stream()
                            .filter(t -> t.data() != null)
                            .map(TransitionWithData::data)
                            .collect(Collectors.toList())
                    );
                    return secondaryIdsToAdd(transitionsWithData).collectList()
                        .flatMap(secondaryIdsToAdd -> correlationId().flatMap(correlationId -> Flux.fromIterable(
                                    transitionsWithData)
                                .flatMap(transition0 -> outgoingRequests(
                                    transition0,
                                    new Entity(entity.id(), join(entity.secondaryIds(), secondaryIdsToAdd), entity.model()),
                                    finalEventsToStore,
                                    effectiveEventLog,
                                    correlationId,
                                    processResults,
                                    processedEvents
                                )).collectList()
                                .zipWith(Flux.fromIterable(transitionsWithData)
                                    .flatMap(transition0 -> outgoingResponses(
                                        transition0,
                                        new Entity(
                                            entity.id(),
                                            join(entity.secondaryIds(), secondaryIdsToAdd),
                                            entity.model()
                                        ),
                                        finalEventsToStore,
                                        effectiveEventLog,
                                        incomingNotification,
                                        processResults,
                                        processedEvents
                                    )).collectList())
                            )
                            .flatMap(outgoingNotifications -> correlationId().map(correlationId -> new ChangeResult(
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
                                        .toList(), new Change(
                                        eventLog.entityModel(),
                                        entityId,
                                        secondaryIds,
                                        currentState,
                                        targetState,
                                        finalEventsToStore,
                                        join(secondaryIdsToAdd, idsForNewEntity),
                                        incomingNotification instanceof Notification.IncomingRequest ir ? List.of(ir)
                                            : List.of(),
                                        outgoingNotifications.getT2(),
                                        outgoingNotifications.getT1(),
                                        incomingNotification instanceof Notification.IncomingResponse ir ? List.of(ir)
                                            : List.of(),
                                        getDeadline(targetState.state()),
                                        correlationId
                                    )
                                )
                            ))));

                  }));
        });
  }

  private List<Event> addTransitionData(List<Event> events, List<?> datas) {
    return events.stream()
        .map(event -> {
              if (event.data() != null || datas.isEmpty())
                return event;
              Object data = datas.stream().filter(d -> event.type().dataType() == d.getClass()).findFirst().orElse(null);
              if (data == null)
                return event;
              return new Event(
                  event.eventNumber(),
                  event.type(),
                  clock,
                  event.messageId(),
                  event.clientId(),
                  data
              );
            }
        )
        .toList();
  }

  private <I, O> Mono<TransitionWithData<I, O>> transitionWithData(
      InputEvent<I> inputEvent,
      ActualTransition<I, O> transition,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Notification incomingNotification
  ) {
    return createData(
        transition.model(),
        entity,
        eventLog,
        newEvents,
        transition.event(),
        new InputEvent<>(inputEvent.eventType(), null, null),
        incomingNotification
    )
        .map(data -> new TransitionWithData<>(transition, data))
        .defaultIfEmpty(new TransitionWithData<>(transition, null));
  }

  private Flux<TransitionWithData<?, ?>> transitionsWithData(
      InputEvent<?> inputEvent,
      List<ActualTransition<Void, ?>> transitions,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Notification incomingNotification
  ) {
    requireNonNull(transitions);
    requireNonNull(entity);
    requireNonNull(eventLog);
    requireNonNull(newEvents);
    return Flux.fromIterable(transitions)
        .flatMap(transition -> transitionWithData(new InputEvent<>(inputEvent.eventType(), null, null), transition, entity, eventLog, newEvents, incomingNotification));
  }

  private <T> Mono<ProcessResult> processEvents(
      EventLog eventLog,
      List<Event> scheduledEvents,
      InputEvent<T> inputEvent,
      Notification incomingNotification,
      List<SecondaryId> idsForNewEntity
  ) {
    return calculateChange(eventLog, scheduledEvents, inputEvent, incomingNotification, idsForNewEntity)
        .onErrorResume(t -> Mono.just(new ChangeResult(
                new ProcessResult(
                    Status.Failed,
                    new Entity(eventLog.entityId(), List.of(), eventLog.entityModel()),
                    null,
                    t.toString()
                ),
                List.of()
            ))
        )
        .switchIfEmpty(Mono.error(new RuntimeException("Empty change result for input event " + inputEvent)))
        .flatMap(changeResult -> switch (changeResult.result().status()) {
              case Accepted -> storeChanges(changeResult.changes(), eventLog)
                  .doOnNext(resultAfterStore -> {
                    if (resultAfterStore.isAccepted())
                      listener.changeAccepted(changeResult.changes().getFirst().correlationId(), toListenerFormat(changeResult.changes()));
                  });
              case Rejected, Repeated, Conflicted, Failed, Raced -> Mono.just(changeResult.result());
            }
        );
  }

  private List<Listener.Change> toListenerFormat(List<Change> changes) {
    return changes.stream().map(change -> new Listener.Change(
                new Listener.Change.Entity(
                    change.entityModel(),
                    change.entityId(),
                    join(change.secondaryIds(), change.newSecondaryIds()).stream().map(SecondaryId::toString).toList()
                ),
                change.sourceState() != null ? change.sourceState().state() : null,
                change.targetState() != null ? change.targetState().state() : null,
                change.deadline(),
                change.newEvents().stream().map(e -> new Listener.Change.Event(
                    e.eventNumber(),
                        e.type(),
                        e.data()
                )).toList(),
                change.newSecondaryIds().stream().map(SecondaryId::toString).toList(),
                change.incomingRequests().stream().map(Notification.IncomingRequest::message).toList(),
                change.outgoingResponses().stream().map(Notification.OutgoingResponse::message).toList(),
                change.outgoingRequests().stream().map(Notification.OutgoingRequest::message).toList(),
                change.incomingResponses().stream().map(Notification.IncomingResponse::message).toList()
            )
        )
        .toList();
  }

  private <E> List<E> join(List<E> list, E element) {
    var l = new ArrayList<E>(list.size() + 1);
    l.addAll(list);
    l.add(element);
    return unmodifiableList(l);
  }

  private <E> List<E> join(E element, List<E> list) {
    var l = new ArrayList<E>(list.size() + 1);
    l.add(element);
    l.addAll(list);
    return unmodifiableList(l);
  }

  private <E> List<E> join(List<E> list1, List<E> list2) {
    var l = new ArrayList<E>(list1.size() + list2.size());
    l.addAll(list1);
    l.addAll(list2);
    return unmodifiableList(l);
  }

  private Mono<ProcessResult> storeChanges(
      List<Change> unfilteredChanges,
      EventLog eventLog
  ) {
    List<Change> changes = unfilteredChanges.stream()
        .filter(change -> !change.newEvents().stream().allMatch(e -> e.type().isReadOnly()))
        .toList();
    return correlationId() // TODO: Already have correlationId in _change_
        // TODO: Not really handling multiple changes at once
        .delayUntil(c -> changes.isEmpty() ?
            Mono.empty() :
            delayer.apply(changes.getLast().newEvents().stream().map(e -> c + "-" + e.typeName()).toList())
        )
        .flatMap(correlationId -> (changes.isEmpty() ? Flux.<ChangeState.OutboxElement>empty() :
            changeState.execute(changes))
            .collectList()
            // Forward outgoing requests (for guaranteed delivery this will be the first attempt)
            .transformDeferredContextual((publisher, ctx) -> publisher
                .doOnNext(outboxElementsToForward -> outboxElementsToForward.forEach(q ->
                        forwardInitial(
                            changes.get(q.changeIndex()),
                            q.elementId(),
                            q.requestId(),
                            changes.get(q.changeIndex()).outgoingRequests().get(q.notificationIndex()),
                            correlationId
                        ).contextWrite(ctx).subscribe()
                    )
                )
            )
            .thenReturn(new ProcessResult(
                Status.Accepted,
                new Entity(unfilteredChanges.getLast().entityId(), unfilteredChanges.getLast().secondaryIds(), unfilteredChanges.getLast().entityModel()),
                unfilteredChanges.getLast().outgoingResponses().stream().map(OutgoingResponse::message).findFirst().orElse(null),
                null
            ))
            .onErrorResume(error -> handleTransitionError(changes, error, eventLog))
        );
  }

  private Mono<ForwardStatus> forwardInitial(
      ChangeState.Change change,
      byte[] queueElementId,
      UUID requestId,
      Notification.OutgoingRequest outgoingRequest,
      String correlationId
  ) {
    var queueElement = new OutboxElement(
        queueElementId,
        requestId,
        change.entityId(),
        change.entityModel(),
        outgoingRequest.eventNumber(),
        outgoingRequest.creatorId(),
        outgoingRequest.queue(),
        outgoingRequest.guaranteed(),
        change.newEvents().getFirst().timestamp(),
        outgoingRequest.message(),
        correlationId,
        1,
        null,
        null
    );
    return doForward(queueElement, outgoingRequest.maxRetryAttempts(), outgoingRequest.retryInterval());
  }

  Mono<ForwardStatus> forward(OutboxElement queueElement) {
    return doForward(queueElement, 0, null).contextWrite(Correlation.contextOf(queueElement.correlationId()));
  }

  private record ResponseValidationResult(Result validationResult, Notification.IncomingResponse response) {}

  private Mono<ForwardStatus> doForward(OutboxElement queueElement, int maxRetryAttempts, Duration retryInterval) {
    HttpRequestMessage requestMessage;
    try {
      requestMessage = ofNullable(outgoingRequestCreators.get(queueElement.creatorId()))
          .filter(_ -> queueElement.attempt() > 1)
          .map(c -> c.repeated(queueElement.data()))
          .orElse(queueElement.data());
    } catch (Exception e) {
      String reason = "Parsing/transforming message failed: " + e.getMessage();
      return moveToDLQ.execute(queueElement, reason)
          .doOnSuccess(_ -> logDead(queueElement, reason))
          .thenReturn(ForwardStatus.Dead);
    }
    EntityModel entityModel = queueElement.entityModel();
    EntityId entityId = queueElement.entityId();
    return eventsByEntityId
        .execute(entityModel, entityId)
        .onErrorMap(e -> new RuntimeException("Failed to get events for entity model " + entityModel.name(), e))
        .flatMap(eventLog -> {
          var responseValidator = findOutgoingRequestModel(
              eventLog,
              queueElement.eventNumber(),
              queueElement.queue(),
              queueElement.creatorId()
          ).responseValidator();
          return clients.apply(queueElement.queue()).exchange(requestMessage)
              .flatMap(responseMessage -> {
                var responseNotification = responseNotification(queueElement, responseMessage);
                return validateResponse(
                    requestMessage,
                    responseMessage,
                    responseValidator,
                    entityId,
                    eventLog.lastEventNumber() + 1,
                    requiredData(
                        responseValidator,
                        new Entity(entityId, eventLog.secondaryIds(), eventLog.entityModel()),
                        eventLog.effectiveEvents(),
                        List.of(),
                        responseNotification
                    )
                ).map(output -> new ResponseValidationResult(output, responseNotification));
              })
              .flatMap(validationOutput -> validationOutput.validationResult().status() == Result.Status.TransientError ?
                  Mono.error(new TransientError(validationOutput)) :
                  Mono.just(validationOutput)
              )
              .retryWhen(RetrySpec.fixedDelay(maxRetryAttempts, retryInterval)
                  .filter(e -> e instanceof TransientError && maxRetryAttempts > 0))
              .onErrorResume(TransientError.class, e -> Mono.just(e.transientResult))
              .flatMap(validationOutput ->
                  (validationOutput.validationResult().inputEvent() != null ?
                      processEvents(
                          eventLog,
                          List.of(),
                          validationOutput.validationResult().inputEvent(),
                          validationOutput.response(),
                          List.of()
                      )
                          .flatMap(processResult -> processResult.status() != Status.Raced ?
                              Mono.just(processResult) :
                              Mono.error(new ProcessEventsRaced())
                          )
                          .retryWhen(RetrySpec.fixedDelay(3, Duration.ofMillis(100))
                              .filter(e -> e instanceof ProcessEventsRaced))
                          .flatMap(processResult -> Mono.deferContextual(ctx -> {
                            if (processResult.responseMessage() != null && hasResponseSink(ctx)) {
                              One<HttpResponseMessage> sink = responseSink(ctx);
                              sink.tryEmitValue(processResult.responseMessage());
                            }
                            return Mono.just(processResult);
                          }))
                          .map(processResult -> new IncomingResponseStatus(
                              validationOutput.response(),
                              processResult,
                              validationOutput.validationResult()
                          )) :
                      Mono.just(new IncomingResponseStatus(
                          validationOutput.response(),
                          new ProcessResult(Status.Rejected, null, null, "No event from response validator " + responseValidator.getClass()),
                          validationOutput.validationResult()
                      )
                  )
              ))
              .flatMap(result -> switch (result.processResult.status()) {
                    case Status.Accepted, Status.Repeated -> Mono.just(ForwardStatus.Ok);
                    case Status.Rejected -> switch (result.validationResult().status()) {
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
                                  ) + " was rejected: " + result.processResult().error()
                          ));
                      case PermanentError -> moveToDLQ.execute(queueElement, result.validationResult().message())
                          .doOnSuccess(_ -> logDead(queueElement, result.validationResult().message()))
                          .thenReturn(ForwardStatus.Ok);
                      case TransientError -> backOffOrDie(queueElement, requireNonNullElse(result.validationResult().message(), "TransientError")).thenReturn(ForwardStatus.Ok);
                    };
                    case Status.Raced -> Mono.error(new IllegalStateException("Raced response not handled"));
                    case Status.Failed ->
                        backOffOrDie(queueElement, result.processResult().error()).thenReturn(ForwardStatus.Ok);
                    default -> Mono.error(new IllegalStateException("Unexpected value: " + result.processResult().status()));
                  }
              );
        })
        .onErrorResume(e -> backOffOrDie(queueElement, e));
  }

  private static class ProcessEventsRaced extends RuntimeException {}

  private OutgoingRequestModel<?, ?> findOutgoingRequestModel(EventLog eventLog, int eventNumber, OutboxQueue queue, UUID requestModelId) {
    TransitionModel<?, ?> transitionForEvent = requireNonNull(transitionForEventNumber(eventLog, eventNumber));
    return Stream.concat(
            transitionForEvent.outgoingRequests().stream(),
            ofNullable(transitionForEvent.reverse()).stream()
                .flatMap(reverseTransition -> reverseTransition.outgoingRequests().stream())
        )
        .filter(notificationSpecification -> {
              if (!notificationSpecification.queue().equals(queue))
                return false;
              OutgoingRequestCreator<?> c = notificationSpecification.notificationCreator();
              if (c == null)
                c = beanRegistry.getBean(notificationSpecification.notificationCreatorType());
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

  private Notification.IncomingResponse responseNotification(OutboxElement queueElement, HttpResponseMessage responseMessage) {
    return new Notification.IncomingResponse(
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
    return backOffOrDie(queueElement, e + (e.getCause() != null ? " (cause: " + e.getCause() + ")" : ""));
  }

  private Mono<ForwardStatus> backOffOrDie(OutboxElement queueElement, String reason) {
    if (queueElement.nextAttemptAt() != null && backoff.isExhausted(queueElement.enqueuedAt(), queueElement.nextAttemptAt(), clock)) {
      return moveToDLQ.execute(queueElement, reason)
              .doOnSuccess(_ -> logDeadByExhaustion(queueElement, reason))
              .thenReturn(ForwardStatus.Dead);
    } else {
      logBackoff(queueElement, reason);
      return Mono.just(ForwardStatus.Backoff);
    }
  }

  private void logDeadByExhaustion(OutboxElement e, String reason) {
    listener.forwardingDeadByExhaustion(e.requestId(), e.entityId(), e.queue().name(), e.enqueuedAt(), e.attempt(), e.eventNumber(), e.correlationId(), reason);
  }

  private void logForwarded(OutboxElement e, HttpResponseMessage responseMessage, String reason) {
    listener.forwardingCompleted(e.requestId(), e.queue().name(), e.enqueuedAt(), e.attempt(), e.entityId(), e.eventNumber(), e.correlationId(), responseMessage, reason);
  }

  private void logBackoff(OutboxElement e, String reason) {
    // TODO: e.backoff() requires processedAt nextAttemptAt
    listener.forwardingBackedOff(e.requestId(), e.queue().name(), e.enqueuedAt(), e.attempt(), e.entityId(), e.eventNumber(), e.correlationId(), reason, e.nextAttemptAt(), e.backoff());
  }

  private void logDead(OutboxElement e, String reason) {
    listener.forwardingDead(e.requestId(), e.entityId(), e.queue().name(), e.enqueuedAt(), e.attempt(), e.eventNumber(), e.correlationId(), reason);
  }

  private <I, O> Flux<Notification.OutgoingRequest> outgoingRequests(
      TransitionWithData<I, O> transition,
      Entity entity,
      List<Event> newEvents,
      List<Event> eventLog,
      String correlationId,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    return Flux.fromIterable(transition.transition().model().outgoingRequests())
        .flatMap(outgoingRequestModel -> createOutgoingRequestNotification(
            transition,
            entity,
            transition.transition().event(),
            newEvents,
            eventLog,
            outgoingRequestModel,
            correlationId,
            processResults,
            processedEvents
        ));
  }

  private <I, O> Flux<Notification.OutgoingResponse> outgoingResponses(
      TransitionWithData<I, O> transition,
      Entity entity,
      List<Event> newEvents,
      List<Event> eventLog,
      Notification incomingNotification,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    return Flux.fromIterable(transition.transition().model().outgoingResponses())
        .flatMap(outgoingResponseModel -> createOutgoingResponseNotification(
            transition,
            entity,
            transition.transition().event(),
            newEvents,
            eventLog,
            outgoingResponseModel,
            incomingNotification,
            processResults,
            processedEvents
        ));
  }

  private record TransitionWithData<I, O>(ActualTransition<I, O> transition, O data) {}

  private Flux<SecondaryId> secondaryIdsToAdd(
      List<TransitionWithData<?, ?>> transitionsWithData
  ) {
    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
    for (var actualTransition : transitionsWithData) {
      secondaryIdFlux = secondaryIdFlux.mergeWith(secondaryIdsToAdd(actualTransition));
    }
    return secondaryIdFlux;
  }

  private <I, O> Flux<SecondaryId> secondaryIdsToAdd(TransitionWithData<I, O> transitionWithData) {
    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
    secondaryIdFlux = secondaryIdFlux.mergeWith(Flux
        .fromIterable(transitionWithData.transition().model().newIdentifiers())
        .map(newId -> newId.apply(transitionWithData.data())));
    return secondaryIdFlux;
  }

  private <I, O, U> Mono<Notification.OutgoingRequest> createOutgoingRequestNotification(
      TransitionWithData<I, O> transition,
      Entity entity,
      Event currentEvent,
      List<Event> newEvents,
      List<Event> eventLog,
      OutgoingRequestModel<O, U> notificationModel,
      String correlationId,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    OutgoingRequestCreator<U> creator = notificationModel.notificationCreatorType() != null ?
        beanRegistry.getBean(notificationModel.notificationCreatorType()) :
        notificationModel.notificationCreator();
    var filteredEvents = new RequiredData(
        entity,
        join(eventLog, newEvents), // Need for timestamp
        processResults,
        processedEvents,
        List.of(),//incomingNotification == null ? List.of() : List.of(incomingNotification),
        Requirements.none(),
        notificationModel.notificationCreatorType() != null ? notificationModel.notificationCreatorType() : notificationModel.notificationCreator().getClass(),
        null,//incomingRequestByEvent,
        null//outgoingRequestByEvent
    );
    Entity parentEntity = filteredEvents.nestedEntities().stream()
        .filter(nestedEntity -> nestedEntity.model().equals(entity.model.parentEntity()))
        .findFirst().orElse(null);
    return creator.create(
            notificationModel.dataAdapter().apply(transition.data()),
            entity.id(),
            correlationId,
            filteredEvents
        )
        .map(message -> new Notification.OutgoingRequest(
            UUID.randomUUID(),
            currentEvent.eventNumber(),
            message,
            notificationModel.queue(),
            creator.id(),
            notificationModel.guaranteed(),
            notificationModel.maxRetryAttempts(),
            notificationModel.retryInterval(),
            parentEntity != null ? parentEntity.id() : null
        ));
  }

  private <I, O, U> Mono<Notification.OutgoingResponse> createOutgoingResponseNotification(
      TransitionWithData<I, O> transition,
      Entity entity,
      Event currentEvent,
      List<Event> newEvents,
      List<Event> eventLog,
      OutgoingResponseModel<O, U> notificationModel,
      Notification incomingNotification,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    OutgoingResponseCreator<U> creator = notificationModel.creatorType() != null ?
        beanRegistry.getBean(notificationModel.creatorType()) :
        notificationModel.creator();
    var filteredEvents = new RequiredData(
        entity,
        join(eventLog, newEvents), // For timestamp
        processResults,
        processedEvents,
        List.of(),//incomingNotification == null ? List.of() : List.of(incomingNotification),
        Requirements.none(),//creator.requirements(),
        creator.getClass(),
        null,//incomingRequestByEvent,
        null//outgoingRequestByEvent
    );
    return Mono.deferContextual(ctx -> !hasRequestId(ctx) ?
        Mono.just(correlationId(ctx) + ": No incoming request in context, so skipping outgoing response " + creator.getClass())
            .doOnNext(System.out::println)
            .then(Mono.empty()) :
        creator.create(
            notificationModel.dataAdapter().apply(transition.data()),
            incomingNotification instanceof Notification.IncomingRequest rq ? rq : null,
            entity.id(),
            correlationId(ctx),
            filteredEvents
        )
        .map(message -> new Notification.OutgoingResponse(
                currentEvent.eventNumber(),
                message,
                requestId(ctx)
            )
        )
    );
  }

  private ZonedDateTime getDeadline(State targetState) {
    return targetState.timeout().map(timeout -> ZonedDateTime.now(clock).plus(timeout.duration())).orElse(null);
  }

  private Mono<ProcessResult> handleTransitionError(
      List<Change> changes,
      Throwable e,
      EventLog eventLog
  ) {
    EntityId entityId = changes.getLast().entityId();
    EntityModel entityModel = changes.getLast().entityModel();
    TraversableState currentState = changes.getLast().sourceState();
    List<Event> newEvents = changes.getLast().newEvents();
    List<Notification.IncomingRequest> incomingRequests = changes.getLast().incomingRequests();
    var event0 = newEvents.getFirst();
    Supplier<Mono<ProcessResult>> handleRaceOrError = () -> {
      if (e instanceof ChangeRaced cr) {
        return withCorrelationId(correlationId -> listener.changeRaced(correlationId, toListenerFormat(changes), cr))
            .thenReturn(new ProcessResult(Status.Raced, new Entity(entityId, List.of(), entityModel), null, null));
      } else {
        return withCorrelationId(correlationId -> listener.changeFailed(correlationId, toListenerFormat(changes), e))
            .thenReturn(new ProcessResult(Status.Failed, new Entity(entityId, List.of(), entityModel), null, e.getMessage()));
      }
    };
    if (e instanceof DuplicateMessage) {
      Notification.IncomingRequest incomingRequest = incomingRequests.getFirst();
      return outgoingResponseByRequest.execute(incomingRequest.messageId(), incomingRequest.clientId())
          .single()
          .flatMap(originalRequest -> (Arrays.equals(originalRequest.requestDigest(), incomingRequest.digest())) ?
              repeatedRequest(
                  entityId,
                  entityModel,
                  incomingRequest.clientId(),
                  incomingRequest.messageId(),
                  originalRequest.responseMessage()
              )
              :
              invalidRequest(
                  incomingRequestNotification(incomingRequest, "C" + incomingRequest.messageId()),
                  eventLog,
                  "Message identifier '" + incomingRequest.messageId() + "' not unique"
              ))
          .switchIfEmpty(handleRaceOrError.get());
    } else if (e instanceof MissingRequirement || e instanceof RequirementsNotFulfilled) {
      return changeState.execute(List.of(new Change(
                  entityModel,
                  entityId,
                  List.of(),
                  null,
                  null,
                  List.of(new Event(event0.eventNumber(), BuiltinEventTypes.InconsistentState, clock, e.getMessage())),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  List.of(),
                  null,
          "N/A"
              ))
          ).collectList()
          .flatMap(_ -> withCorrelationId(correlationId -> listener.inconsistentState(correlationId, entityId, currentState.toString(), e.getMessage())))
          .thenReturn(new ProcessResult(Status.Failed, new Entity(entityId, List.of(), entityModel), null, e.getMessage()));
    } else {
      return handleRaceOrError.get();
    }
  }

  private record ActualTransition<I, O>(
      TransitionModel<I, O> model,
      Event event
  ) {}

  private TraversableState traverseTo(int eventNumber, EntityModel entityModel, List<Event> eventLog) {
    TraversableState state = begin(entityModel);
    // Skip till eventNumber
    for (var event : eventLog) {
      if (event.eventNumber() <= eventNumber) {
        state = state.forward(event.type());
      }
    }
    return state;
  }

  /*
   * Get the list of transitions and their events from event log starting from startEventNumber.
   */
  private List<ActualTransition<Void, ?>> actualTransitions(
      int startEventNumber,
      EntityModel entityModel,
      List<Event> eventLog,
      List<Event> newEvents,
      List<Event> transitionEvents,
      boolean reverse
  ) {
    // Skip till startEventNumber
    TraversableState state = traverseTo(startEventNumber, entityModel, eventLog);
    List<ActualTransition<Void, ?>> list = new ArrayList<>();
    for (var event : transitionEvents) {
      TransitionModel<Void, ?> transition = (TransitionModel<Void, ?>) requireNonNull(state.transition(event.type()), "Transition for " + event.type() + " from " + state.state() + " not allowed");
      if (reverse && !newEvents.contains(event)) // New events never trigger reverse transitions
        transition = transition.reverse();
      if (transition != null) // Reverse transitions might not be defined
        list.add(new ActualTransition<>(transition, event));
      state = state.forward(event.type());
    }
    return list;
  }

  private <I, O> Mono<O> createData(
      TransitionModel<I, O> transitionModel,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Event transitionEvent,
      InputEvent<I> inputEvent,
      Notification incomingNotification
  ) {
    requireNonNull(transitionModel, "transitionModel is null for transitionEvent " + transitionEvent);
    DataCreator<I, O> dataCreator = dataCreator(transitionModel);
    if (dataCreator != null) {
      RequiredData requiredData = requiredData(dataCreator, entity, eventLog, newEvents, incomingNotification);
      return dataCreator.execute(inputEvent, requiredData);
    }
    return Mono.empty();
  }

  private <I, O> DataCreator<I, O> dataCreator(TransitionModel<I, O> transitionModel) {
    DataCreator<I, O> dataCreator = transitionModel.dataCreator();
    if (dataCreator == null && transitionModel.dataCreatorType() != null)
      dataCreator = beanRegistry.getBean(transitionModel.dataCreatorType());
    return dataCreator;
  }

  private List<EventType> scheduledEvents(Entity entity, List<Event> eventLog, ZonedDateTime deadline) {
    var scheduledEvents = new ArrayList<EventType>();
    for (var actualTransition : actualTransitions(0, entity.model(), eventLog, List.of(), eventLog, false)) {
      scheduledEvents.addAll(
          actualTransition.model().scheduledEvents().stream()
              .filter(se -> !actualTransition.event().timestamp().plus(se.deadline()).isAfter(deadline))
              .map(ScheduledEvent::type)
              .toList()
      );
    }
    return unmodifiableList(scheduledEvents);
  }

  private boolean isPendingIncomingResponse(EventLog eventLog) {
    return false;
//    TransitionModel<?> lastTransition = lastTransition(eventLog);
//    return lastTransition != null && lastTransition.outgoingRequests()
//        .stream().anyMatch(s -> s.responseValidator() != null);
  }

//  private TransitionModel<?> lastTransition(EventLog eventLog) {
//    var events = eventLog.events();
//    if (events.isEmpty()) return null;
//    var nextToLastState = eventLog.entityModel().begin().forward(events.subList(0, events.size()-1).stream().map(Event::getType).toList());
//    return requireNonNull(nextToLastState).transition(events.getLast().getType());
//  }

  private TransitionModel<?, ?> transitionForEventNumber(EventLog eventLog, int eventNumber) {
    var events = eventLog.events();
    if (events.isEmpty()) return null;
    TraversableState state = begin(eventLog.entityModel());
    for (var event : events) {
      if (event.eventNumber() == eventNumber)
        return state.transition(event.type());
      state = state.forward(event.type());
    }
    throw new IllegalStateException("Event number " + eventNumber + " not found in event log");
  }

  private TraversableState traverseTo(EntityModel entityModel, List<Event> eventLog, int eventNumber) {
    if (eventNumber == 0)
      return begin(entityModel);
    var state = begin(entityModel);
    for (var event : eventLog) {
      state = state.forward(event.type());
      if (event.eventNumber() == eventNumber)
        return state;
    }
    throw new IllegalArgumentException("No event with number " + eventNumber);
  }

  private EntityId newEntityId() {
    return new EntityId.UUID(UUID.randomUUID());
  }

  private TraversableState begin(EntityModel model) {
    return TraversableState.create(model);
  }

}
