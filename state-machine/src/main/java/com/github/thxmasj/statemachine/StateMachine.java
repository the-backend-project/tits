package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.Correlation.correlationId;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.DuplicateMessage;
import com.github.thxmasj.statemachine.database.EntityGroupNotInitialised;
import com.github.thxmasj.statemachine.database.MappingFailure;
import com.github.thxmasj.statemachine.database.UnknownEntity;
import com.github.thxmasj.statemachine.EventTrigger.EntitySelector;
import com.github.thxmasj.statemachine.IncomingRequestValidator.Context;
import com.github.thxmasj.statemachine.Notification.OutgoingResponse;
import com.github.thxmasj.statemachine.OutboxWorker.ForwardStatus;
import com.github.thxmasj.statemachine.OutboxWorker.ResponseEvaluator.EvaluatedResponse;
import com.github.thxmasj.statemachine.RequiredData.RequirementsNotFulfilled;
import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Status;
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
import com.github.thxmasj.statemachine.database.mssql.IncomingResponseByEvent;
import com.github.thxmasj.statemachine.database.mssql.LastSecondaryId;
import com.github.thxmasj.statemachine.database.mssql.MoveToDLQ;
import com.github.thxmasj.statemachine.database.mssql.NextDeadline;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.OutgoingResponseAndRequestDigestByRequest;
import com.github.thxmasj.statemachine.database.mssql.ProcessBackedOff;
import com.github.thxmasj.statemachine.database.mssql.ProcessNew;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.database.mssql.SecondaryIdByEntityId;
import com.github.thxmasj.statemachine.http.HttpClient.TimeoutException;
import com.github.thxmasj.statemachine.http.RequestMapper;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpMessageParser;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
  private final IncomingResponseByEvent incomingResponseByEvent;

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
      boolean singleClientPerEntity
  ) {
    this.requestMapper = requestMapper;
    this.delayer = delayer != null ? delayer : _ -> Mono.empty();
    this.beanRegistry = beanRegistry;
    // Schema must be created before OutboxWorker/Resolver attempts to use it and before DatabaseAccess validates PKs
    if (schemaDataSource != null) {
      new CreateSchema(entityModels, schemaName, role).execute(new JDBCClient(schemaDataSource)).blockOptional(ofSeconds(10));
    }
    this.clock = clock;
    var jdbcClient = new JDBCClient(dataSource);
    this.changeState = new ChangeState(jdbcClient, schemaName, clock);
    this.eventsByEntityId = new EventsByEntityId(dataSource, entityModels, schemaName, clock);
    this.eventsByLookupId = new EventsByLookupId(dataSource, entityModels, schemaName, clock);
    this.eventsByMessageId = new EventsByMessageId(dataSource, entityModels, schemaName, clock);
    this.eventsByLastEntity = new EventsByLastEntity(dataSource, entityModels, schemaName, clock);
    this.secondaryIdByEntityId = new SecondaryIdByEntityId(dataSource, entityModels, schemaName);
    this.lastSecondaryId = new LastSecondaryId(dataSource, entityModels, schemaName);
    this.dequeueAndStoreReceipt = new DequeueAndStoreReceipt(jdbcClient, entityModels, schemaName, clock);
    this.moveToDLQ = new MoveToDLQ(jdbcClient, entityModels, schemaName);
    this.nextDeadline = new NextDeadline(jdbcClient, clock, entityModels, schemaName);
    this.outgoingResponseByRequest = new OutgoingResponseAndRequestDigestByRequest(dataSource, entityModels, schemaName);
    this.incomingRequestByEvent = new IncomingRequestByEvent(dataSource, entityModels, schemaName);
    this.outgoingRequestByEvent = new OutgoingRequestByEvent(dataSource, entityModels, schemaName);
    this.incomingResponseByEvent = new IncomingResponseByEvent(dataSource, entityModels, schemaName);
    this.listener = listener;
    this.singleClientPerEntity = singleClientPerEntity;
    // TODO: Differentiate delay spec per subscriber
    var backoff = new DelaySpecification(ofSeconds(10), ofSeconds(20), ofSeconds(100), 1.5);
    var processNew = new ProcessNew(jdbcClient, entityModels, schemaName, clock, backoff);
    var processBackedOff = new ProcessBackedOff(jdbcClient, entityModels, schemaName, clock, backoff);
    for (var entityModel : entityModels) {
      Looper<ResolverStatus> resolverLooper = new Looper<>(
          "ResolverWorker-" + entityModel.name(),
          false,
          () -> resolveState(entityModel).flux().switchIfEmpty(Flux.just(ResolverStatus.Empty)),
          status -> switch (status) {
            case Ok -> Duration.ZERO;
            case Empty -> Duration.ofSeconds(1);
            case Error -> Duration.ofSeconds(10);
          }
      );
      resolverLooper.loop();
      workers.add(resolverLooper);
      for (var subscriber : entityModel.subscribers()) {
        var looper = new OutboxWorker(
            this,
            processNew,
            processBackedOff,
            entityModel,
            listener,
            subscriber,
            schemaName,
            clock
        ).forwarder(true);
        looper.loop();
        workers.add(looper);
      }
    }
  }

  private EventLog emptyEventLog(EntityModel entityModel) {
    return new EventLog(entityModel, entityModel.newEntityId(), List.of(), List.of());
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
      IncomingRequestModelBuilder<?> requestModelBuilder = requestMapper.incomingRequest(message);
      if (requestModelBuilder == null) {
        return Mono.just(new ProcessResult(Status.Rejected, null, null, String.format("No mapping for incoming message: %s", message.requestLine())));
      }
      IncomingRequestModel requestModel = requestModelBuilder.build();
      var incomingRequest = new IncomingRequest(
          requestModel.eventTrigger().build(), // TODO: Errors end up in the big try/catch. Related: https://github.com/orgs/the-backend-project/projects/1/views/1?pane=issue&itemId=79673689
          requestModel.clientId(),
          requestModel.derivedMessageId() ? (entityId, eventType) -> entityId.value() + "-" + eventType.id() : (_, _) -> requestModel.messageId(),
          requestModel.digest() != null ? requestModel.digest() : MessageDigest.getInstance("SHA256").digest(message.message().getBytes(StandardCharsets.UTF_8)),
          message,
          requestModel.validatorClass() != null ? beanRegistry.getBean(requestModel.validatorClass()) : requestModel.validator()
      );
      return (switch (incomingRequest.eventTrigger().eventType()) {
        case EventType type when type.isCancel() || type.isRollback() -> doProcessRollback(1, incomingRequest);
        case EventType _ -> doProcessIncomingRequest(1, incomingRequest);
      }).contextWrite(Correlation.correlationIdContext(
          requireNonNullElse(requestModel.correlationId(), requireNonNullElse(message.headerValue("X-Correlation-Id"), "processRequest"))
      ));
    } catch (Exception e) {
      listener.clientRequestFailed("N/A", null, null, e);
      return Mono.just(new ProcessResult(Status.Failed, null, null, "Request failed: " + e.toString()));
    }
  }

  public enum ResolverStatus {Ok, Empty, Error}

  /**
   * Resolve a state that has reached its deadline, as indicated by its timeout value.
   */
  public Mono<ResolverStatus> resolveState(EntityModel entityModel) {
    var backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);
    return nextDeadline.execute(entityModel, backoff)
        .zipWhen(deadline -> eventsByEntityId.execute(entityModel, deadline.entityId()))
        .flatMap(deadlineAndEventLog -> {
          Deadline deadline = deadlineAndEventLog.getT1();
          EventLog eventLog = deadlineAndEventLog.getT2();
          if (eventLog.events().getLast().getEventNumber() != deadline.eventNumber()) {
            // The state has already been resolved by another resolver or incoming request
            return Mono.just(ResolverStatus.Ok);
          }
          var currentState = entityModel.begin().forward(eventLog.events().stream().map(Event::getType).toList());
          var timeoutEvent = new Event(deadline.eventNumber() + 1, currentState.state().timeout().get().eventType(), clock);
          return processEvents(
              eventLog,
              List.of(timeoutEvent),
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
                case Failed -> backoff.isExhausted(eventLog.events().getLast().getTimestamp(), deadline.nextAttemptAt(), clock) ?
                    Mono.error(new RuntimeException("Period for state resolving exhausted: " + Duration.between(eventLog.events().getLast().getTimestamp(), ZonedDateTime.now(clock)))) :
                    Mono.just(ResolverStatus.Ok);
              }
          ).contextWrite(Correlation.correlationIdContext(deadline.correlationId()));
        })
        .onErrorReturn(MappingFailure.class, ResolverStatus.Error)
        .doOnError(listener::processNextDeadlineFailed)
        .onErrorReturn(ResolverStatus.Error)
        .switchIfEmpty(Mono.just(ResolverStatus.Empty));
  }

  private Mono<ProcessResult> invalidRequest(
      String clientId,
      String messageId,
      String requestData,
      byte[] digest,
      EventLog eventLog,
      String reason
  ) {
    return processEventForIncomingRequest(
        BuiltinEventTypes.InvalidRequest,
        clientId,
        messageId,
        requestData,
        digest,
        eventLog,
        reason
    );
  }

  private Mono<ProcessResult> rejectedRequest(
      String messageId,
      String clientId,
      String requestData,
      byte[] digest,
      EventLog eventLog,
      String reason
  ) {
    return processEventForIncomingRequest(
        BuiltinEventTypes.RejectedRequest,
        clientId,
        messageId,
        requestData,
        digest,
        eventLog,
        reason
    );
  }

  private Mono<ProcessResult> processEventForIncomingRequest(
      EventType eventType,
      String clientId,
      String messageId,
      String requestData,
      byte[] digest,
      EventLog eventLog,
      String reason
  ) {
    return processEvents(
        eventLog,
        List.of(new Event(eventLog.lastEventNumber() + 1, eventType, clock, messageId, null, reason)),
        new Notification.IncomingRequest(eventLog.lastEventNumber() + 1, requestData, messageId, clientId != null ? clientId : "Unknown", digest),
        List.of()
    );
  }

  private Mono<ProcessResult> repeatedRequest(EntityId entityId, EntityModel entityModel, String clientId, String messageId, String responseMessage) {
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

  private Mono<ProcessResult> doProcessIncomingRequest(
      int attempt,
      IncomingRequest incomingRequest
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
          var currentState = eventTrigger.entityModel().begin().forward(eventLog.effectiveEvents().stream().map(Event::getType).toList());
          List<Event> scheduledEvents = scheduledEvents(eventTrigger.entityModel(), eventLog);
          int nextEventNumber = eventLog.lastEventNumber() + scheduledEvents.size() + 1;
          String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventType());
          if (eventTrigger.eventType() != BuiltinEventTypes.Status && singleClientPerEntity && events.stream()
              .anyMatch(e -> e.isIncomingRequest() && !Objects.equals(e.getClientId(), incomingRequest.clientId()))) {
            return invalidRequest(
                incomingRequest.clientId(),
                messageId,
                incomingRequest.requestMessage().message(),
                incomingRequest.digest(),
                eventLog,
                "Client not allowed"
            );
          }
          if (isRolledBack(eventLog, incomingRequest.clientId(), messageId)) {
            return rejectedRequest(
                messageId,
                incomingRequest.clientId(),
                incomingRequest.requestMessage().message(),
                incomingRequest.digest(),
                eventLog,
                "Request already rolled back");
          }
          Notification requestNotification = new Notification.IncomingRequest(
              nextEventNumber,
              incomingRequest.requestMessage().message(),
              messageId,
              incomingRequest.clientId(),
              incomingRequest.digest()
          );
          var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream().map(Event::getType).toList());
          if (stateAfterScheduledEvents == null) {
            // TODO: Handle better. InconsistentState?
            throw new RuntimeException("Can't apply scheduled events " +
                scheduledEvents.stream().map(Event::getTypeName).collect(joining(",")) +
                " to state " + currentState.state());
          }
          // TODO: Need to check that state accepts the event for the request before validation, otherwise
          //       validation can fail with RequirementsNotFulfilled. This duplicates the behavior, though
          //       (see below, rejectIfNotRepeat called twice).
          if (stateAfterScheduledEvents.forward(eventTrigger.eventType()) == null) {
            return rejectIfNotRepeat(entityId, eventLog, messageId, incomingRequest, "State " + eventTrigger.entityModel() + "/" + stateAfterScheduledEvents.state() + " does not accept " + eventTrigger.eventType());
          }
          return validateRequest(
              eventLog.entityId(),
              messageId,
              incomingRequest,
              nextEventNumber,
              eventTrigger.eventType(),
              requiredData(
                  incomingRequest.validator(),
                  new Entity(eventLog.entityId(), eventLog.secondaryIds(), eventLog.entityModel()),
                  eventLog.effectiveEvents(),
                  scheduledEvents,
                  null,
                  requestNotification
              )
          )
              .flatMap(validationOutput -> processEvents(
                  eventLog,
                  join(scheduledEvents, validationOutput),
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
                  processResult,
                  isPendingIncomingResponse(eventLog)
              ))
              .switchIfEmpty(
                  reattemptDelay.then(doProcessIncomingRequest(attempt + 1, incomingRequest))
              );
        })
        .onErrorResume(UnknownEntity.class, e -> {
          EntityId entityId = e.id() != null ? e.id() : eventTrigger.entityModel().newEntityId();
          String messageId = incomingRequest.messageId().apply(entityId, eventTrigger.eventType());
          return processEvents(
              emptyEventLog(eventTrigger.entityModel(), entityId),
              List.of(new Event(
                  1,
                  BuiltinEventTypes.UnknownEntity,
                  clock,
                  messageId,
                  incomingRequest.clientId()
              )),
              new Notification.IncomingRequest(
                  1,
                  incomingRequest.requestMessage().message(),
                  messageId,
                  incomingRequest.clientId(),
                  incomingRequest.digest()
              ),
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

  private Mono<ProcessResult> rejectIfNotRepeat(
      EntityId entityId,
      EventLog eventLog,
      String messageId,
      IncomingRequest incomingRequest,
      String errorMessage
  ) {
    return outgoingResponseByRequest.execute(eventLog.entityModel(), messageId, incomingRequest.clientId())
        .flatMap(inboxEntry -> Arrays.equals(inboxEntry.requestDigest(), incomingRequest.digest()) ?
            repeatedRequest(
                entityId,
                eventLog.entityModel(),
                incomingRequest.clientId(),
                messageId,
                inboxEntry.responseMessage()
            ) :
            invalidRequest(
                incomingRequest.clientId(),
                messageId,
                incomingRequest.requestMessage().message(),
                incomingRequest.digest(),
                eventLog,
                "Message identifier not unique"
            )
        )
        // Request is not a repeat, so reject it.
        .switchIfEmpty(rejectedRequest(
            'T' + messageId, // don't include temporary rejected incoming requests in repeat check
            incomingRequest.clientId(),
            incomingRequest.requestMessage().message(),
            incomingRequest.digest(),
            eventLog,
            errorMessage
        ));
  }

  private Mono<Event> validateRequest(
      EntityId entityId,
      String messageId,
      IncomingRequest request,
      int currentEventNumber,
      EventType validEventType,
      RequiredData requiredData
  ) {
    var incomingRequest = new Input.IncomingRequest(
        request.requestMessage(),
        messageId,
        request.clientId(),
        currentEventNumber
    );
    return request.validator().execute(
        entityId,
        new IncomingRequestContext<>(
            currentEventNumber,
            validEventType,
            messageId,
            request.clientId()
        ),
        incomingRequest,
        requiredData
    );
  }

  private Mono<Event> validateResponse(
      IncomingResponse response,
      int currentEventNumber,
      RequiredData requiredData
  ) {
    var incomingResponse = new Input.IncomingResponse(
        response.responseMessage(),
        currentEventNumber
    );
    return response.validator().execute(
        response.entityId(),
        new IncomingResponseContext<>(
            currentEventNumber
        ),
        incomingResponse,
        requiredData
    );
  }

  private class IncomingRequestContext<DATA_TYPE> implements Context<DATA_TYPE> {

    private final int eventNumber;
    private final EventType validRequestEventType;
    private final String messageId;
    private final String clientId;

    private IncomingRequestContext(
        int eventNumber,
        EventType validRequestEventType,
        String messageId,
        String clientId
    ) {
      this.eventNumber = eventNumber;
      this.validRequestEventType = validRequestEventType;
      this.messageId = messageId;
      this.clientId = clientId;
    }

    @Override
    public Event invalidRequest(DATA_TYPE data, String errorMessage) {
      return new Event(eventNumber, BuiltinEventTypes.InvalidRequest, clock, messageId, clientId, errorMessage, data);
    }

    @Override
    public Event invalidRequest(String errorMessage) {
      return new Event(eventNumber, BuiltinEventTypes.InvalidRequest, clock, messageId, clientId, errorMessage);
    }

    @Override
    public Event invalidRequest(EventType eventType, DATA_TYPE data, String errorMessage) {
      return new Event(eventNumber, eventType, clock, messageId, clientId, errorMessage, data);
    }

    @Override
    public Event invalidRequest(EventType eventType, DATA_TYPE data) {
      return new Event(eventNumber, eventType, clock, messageId, clientId, data);
    }

    @Override
    public Event validRequest(DATA_TYPE data) {
      return new Event(eventNumber, validRequestEventType, clock, messageId, clientId, data);
    }

    @Override
    public Event validRequest() {
      return new Event(eventNumber, validRequestEventType, clock, messageId, clientId);
    }

  }

  private class IncomingResponseContext<DATA_TYPE> implements IncomingResponseValidator.Context<DATA_TYPE> {

    private final int eventNumber;

    private IncomingResponseContext(
        int eventNumber
    ) {
      this.eventNumber = eventNumber;
    }

    @Override
    public Event requestUndelivered(String cause) {
      return new Event(eventNumber, BuiltinEventTypes.RequestUndelivered, clock, cause);
    }

    @Override
    public Event validResponse(EventType eventType, DATA_TYPE data) {
      return new Event(eventNumber, eventType, clock, data);
    }

    @Override
    public Event invalidResponse(String cause) {
      return new Event(eventNumber, BuiltinEventTypes.InvalidResponse, clock, cause);
    }

    @Override
    public Event rollback(String cause) {
      return new Event(eventNumber, BuiltinEventTypes.Rollback, clock);
    }
  }

  private RequiredData requiredData(
      DataRequirer dataRequirer,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Event currentEvent,
      Notification incomingNotification
  ) {
    Event triggerEvent = newEvents.isEmpty() ? null : newEvents.getLast(); // TODO: Should always be a trigger event
    return new RequiredData(
        entity,
        join(eventLog, newEvents),
        currentEvent,
        triggerEvent,
        List.of(),
        List.of(),
        incomingNotification != null ? List.of(incomingNotification) : List.of(),
        dataRequirer.requirements(),
        dataRequirer.getClass(),
        incomingRequestByEvent,
        outgoingRequestByEvent,
        incomingResponseByEvent
    );
  }

  private Mono<ProcessResult> doProcessIncomingResponse(
      int attempt,
      EntityModel entityModel,
      EntityId entityId,
      Notification.IncomingResponse responseNotification
  ) {
    return eventsByEntityId.execute(entityModel, entityId).flatMap(eventLog -> {
      // TODO: Consistency check/error handling for invalid event logs
      // TODO: Consistency check/error handling for missing model/notification specifications (shift left)
      Event requestEvent = eventLog.events().stream().filter(e -> e.getEventNumber() == responseNotification.eventNumber() - 1).findFirst().orElseThrow();
      if (eventLog.lastEventNumber() != requestEvent.getEventNumber()) {
        return Mono.empty(); // Outbox worker will handle the response
      }
      var effectiveEventLog = eventLog.effectiveEvents();
      var currentState = entityModel.begin().forward(effectiveEventLog.stream().map(Event::getType).toList());
      var requestTransition = lastTransition(eventLog);
      if (requestTransition == null) {
        return Mono.empty();
      }
      var requestNotificationSpecification = requestTransition.outgoingRequests()
          .stream()
          .filter(notificationSpecification -> notificationSpecification.subscriber() != null && notificationSpecification.subscriber().equals(responseNotification.subscriber()))
          .findFirst()
          .orElseThrow();
      if (requestNotificationSpecification.responseValidator() == null) {
        return Mono.empty();
      }
      var incomingResponse = new IncomingResponse(
          entityId,
          new HttpResponseMessage(responseNotification.message()),
          requestNotificationSpecification.responseValidator()
      );
      if (currentState.state().timeout()
          .map(timeout -> effectiveEventLog.getLast().getTimestamp().plus(timeout.duration()))
          .filter(ZonedDateTime.now(clock)::isAfter)
          .isPresent()) {
        return Mono.just(new ProcessResult(
            Status.Rejected,
            new Entity(entityId, List.of(), entityModel),
            null,
            "State changed (timeout) during notification exchange"
        ));
      } else if (!scheduledEvents(new Entity(eventLog.entityId(), eventLog.secondaryIds(), eventLog.entityModel()), eventLog.effectiveEvents(), ZonedDateTime.now(clock)).isEmpty()) {
        return Mono.just(new ProcessResult(
            Status.Rejected,
            new Entity(entityId, List.of(), entityModel),
            null,
            "State changed (scheduled) during notification exchange"
        ));
      }
      return validateResponse(
          incomingResponse,
          requestEvent.getEventNumber() + 1,
          requiredData(
              incomingResponse.validator(),
              new Entity(eventLog.entityId(), eventLog.secondaryIds(), eventLog.entityModel()),
              effectiveEventLog,
              List.of(),
              null,
              responseNotification
          )
      )
          .flatMap(validationOutput -> processEvents(
              eventLog,
              List.of(validationOutput),
              responseNotification,
              List.of()
          ))
          // No point repeating for pending state change when processing responses, as no events are allowed between a
          // request and a response event.
          .filter(pr -> isUnrepeatable(attempt, pr, false))
          .switchIfEmpty(reattemptDelay.then(
              doProcessIncomingResponse(attempt + 1, entityModel, entityId, responseNotification)
          ));
    }).onErrorResume(t -> withCorrelationId(correlationId -> listener.notificationResponseFailed(correlationId, entityId, responseNotification, t)).then(Mono.error(t)));
  }

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
                return Mono.just(emptyEventLog(eventTrigger.entityModel(), entityModel.newEntityId()));
              } else {
                return Mono.error(e);
              }
            }
        )
        .flatMap(eventLog -> {
          int rollbackToEventNumber = eventTrigger.entitySelectors().getFirst().messageId() != null ?
              eventLog.effectiveEvents()
                  .stream()
                  .filter(e -> eventTrigger.entitySelectors().getFirst().messageId().equals(e.getMessageId()))
                  .map(event -> event.getEventNumber() - 1)
                  .findFirst()
                  .orElse(0) :
              0;
          var currentState = entityModel.begin()
              .forward(eventLog.effectiveEvents().stream().map(Event::getType).toList());
          if (currentState == null)
            throw new RuntimeException(
                "Cannot traverse from " + entityModel.begin().state() + " with " + eventLog.events()
                    .stream()
                    .map(Event::getTypeName)
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
              .anyMatch(e -> e.getEventNumber() > rollbackToEventNumber + 1 && e.isIncomingRequest())) {
            return rejectedRequest(
                messageId,
                incomingRequest.clientId(),
                incomingRequest.requestMessage().message(),
                incomingRequest.digest(),
                eventLog,
                "Cannot roll back to event number " + rollbackToEventNumber + " as that would roll back more than one request"
            );
          }
          var newEvents = List.of(new Event(
              lastEventNumber + 1,
              incomingRequest.eventTrigger().eventType(),
              clock,
              messageId,
              incomingRequest.clientId(),
              rollbackToEventNumber + 1
          ));
          Notification incomingNotification = new Notification.IncomingRequest(
              lastEventNumber + 1,
              incomingRequest.requestMessage().message(),
              messageId,
              incomingRequest.clientId(),
              incomingRequest.digest()
          );
          return processEvents(eventLog, newEvents, incomingNotification, List.of())
              .flatMap(result -> result.status() == Status.Rejected ?
                  rejectedRequest(
                      messageId,
                      incomingRequest.clientId(),
                      incomingRequest.requestMessage().message(),
                      incomingRequest.digest(),
                      eventLog,
                      result.error()
                  ) :
                  // https://github.com/orgs/the-backend-project/projects/1/views/1?pane=issue&itemId=91389249
                  //rejectIfNotRepeat(entityId, eventLog, incomingRequest, result.error()) :
                  Mono.just(result)
              )
              .filter(pr -> isUnrepeatable(attempt, pr, isPendingIncomingResponse(eventLog)))
              .switchIfEmpty(reattemptDelay.then(
                  doProcessRollback(attempt + 1, incomingRequest)
              ));
        })
        .onErrorResume(t -> withCorrelationId(correlationId -> listener.rollbackFailed(correlationId, null, t)).then(Mono.error(t)));
  }

  private boolean isUnrepeatable(int attempt, ProcessResult processResult, boolean stateIsPendingChange) {
    // A rejected event might be repeatable if current state is pending change shortly.
    // Using 100 reattempts, then, as we delay for 100 ms to cover changes arriving within 10 seconds (which should
    // cover synchronous notification exchanges). TODO: Use a backoff algorithm instead in that case
    return
        !(processResult.status() == Status.Rejected && attempt < 100 && stateIsPendingChange) &&
        !(processResult.status() == Status.Raced && attempt < 3);
  }

  private Mono<Void> withCorrelationId(Consumer<String> consumer) {
    return correlationId().doOnNext(consumer).then();
  }

  public record ProcessResult(
      Status status,
      Entity entity,
      String responseMessage,
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

    boolean isRepeated() {
      return status == Status.Repeated;
    }

  }

  private <T> Flux<ChangeResult> calculateNestedChanges(Entity entity, TransitionWithData<T> transitionWithData) {
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
      case EntitySelector s when s.secondaryId() != null && s.create() -> calculateNewIds(rootEntity, allSelectors).map(newIds -> new EventLog(entityModel, entityModel.newEntityId(), newIds, List.of()));
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
              var currentState = entityModel.begin()
                  .forward(eventLog.effectiveEvents().stream().map(Event::getType).toList());
              int lastEventNumber = eventLog.lastEventNumber();
              List<Event> scheduledEvents = scheduledEvents(entityModel, eventLog);
              var requestEvent = eventData != null ?
                  new Event(lastEventNumber + scheduledEvents.size() + 1, eventType, clock, eventData) :
                  new Event(lastEventNumber + scheduledEvents.size() + 1, eventType, clock);
              var stateAfterScheduledEvents = currentState.forward(scheduledEvents.stream()
                  .map(Event::getType)
                  .toList());
              if (stateAfterScheduledEvents == null) {
                // TODO: Handle better. InconsistentState?
                return Mono.error(new RuntimeException("Can't apply scheduled events " +
                    scheduledEvents.stream().map(Event::getTypeName).collect(joining(",")) +
                    " to state " + currentState.state()));
              }
              return calculateChange(
                  eventLog,
                  join(scheduledEvents, requestEvent),
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
        List.of(new Event(1, eventType, clock, eventData)),
        null,
        idsForNewEntity
    );
  }

  private List<Event> scheduledEvents(EntityModel entityType, EventLog eventLog) {
    return entityType.begin().forward(eventLog.effectiveEvents().stream().map(Event::getType).toList()).state().timeout()
        .filter(timeout -> ZonedDateTime.now(clock).isAfter(eventLog.effectiveEvents().getLast().getTimestamp().plus(timeout.duration())))
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

  private Mono<ChangeResult> calculateChange(
      EventLog eventLog,
      List<Event> newEvents,
      Notification incomingNotification,
      List<SecondaryId> idsForNewEntity
  ) {
    List<Event> effectiveEvents = eventLog.effectiveEvents();
    EntityId entityId = eventLog.entityId();
    List<SecondaryId> secondaryIds = eventLog.secondaryIds();
    Entity entity = new Entity(
        entityId,
        join(secondaryIds, idsForNewEntity),
        eventLog.entityModel()
    );
    var currentState = eventLog.entityModel().begin().forward(effectiveEvents.stream().map(Event::getType).toList());
    if (currentState.forward(newEvents.stream().map(Event::getType).toList()) == null)
      return Mono.just(new ChangeResult(new ProcessResult(
          Status.Rejected,
          new Entity(entityId, List.of(), eventLog.entityModel()),
          null,
          "State " + eventLog.entityModel() + "[id=" + eventLog.entityId().value() + "]:" + currentState.state() + " does not accept " + newEvents.stream().map(Event::getType).toList()
      ), null));
    List<Event> transitionEvents;
    List<Event> eventsToStore;
    TraversableState targetState;
    int startEventNumber;
    boolean reverseTransitions;
    if (newEvents.getLast().getType().isRollback()) {
      int numberOfEventToRollback;
      if (newEvents.getLast().getData() == null) {
        // No reference to an event to roll back to so roll back to previous state
        TraversableState previousState = currentState;
        numberOfEventToRollback = eventLog.lastEventNumber();
        for (Event event : effectiveEvents.reversed()) {
          numberOfEventToRollback = event.getEventNumber();
          previousState = previousState.backward(event.getType());
          if (!previousState.state().isChoice())
            break;
        }
      } else
          numberOfEventToRollback = Integer.parseInt(newEvents.getLast().getData());
      if (numberOfEventToRollback > 0) {
        startEventNumber = numberOfEventToRollback - 1;
        targetState = traverseTo(eventLog.entityModel(), effectiveEvents, numberOfEventToRollback - 1);
        transitionEvents = Event.join(effectiveEvents.subList(numberOfEventToRollback - 1, effectiveEvents.size()), newEvents);
        reverseTransitions = true;
        eventsToStore = Event.join(newEvents, transitionEvents.stream().filter(e -> !e.getType().isReversible()).toList());
      } else {
        // Rollback arrived before incoming request
        startEventNumber = eventLog.lastEventNumber();
        targetState = currentState;
        transitionEvents = newEvents;
        reverseTransitions = false;
        eventsToStore = newEvents;
      }
    } else if (newEvents.getLast().getType().isCancel()) {
      startEventNumber = 0;
      targetState = eventLog.entityModel().begin();
      transitionEvents = Event.join(effectiveEvents, newEvents);
      reverseTransitions = true;
      eventsToStore = newEvents;
    } else {
      startEventNumber = eventLog.lastEventNumber();
      targetState = currentState.forward(newEvents.stream().map(Event::getType).toList());
      if (targetState == null)
        return Mono.just(new ChangeResult(new ProcessResult(Status.Rejected, entity, null, null), null));
      if (targetState.state().isChoice()) {
        return executeAction(
            entity,
            new EventLog(eventLog.entityModel(), entityId, List.of(), Event.join(effectiveEvents, newEvents)),
            beanRegistry.getBean(targetState.state().choice().action()),
            targetState.state(),
            incomingNotification != null ? List.of(incomingNotification) : List.of()
        )
            .flatMap(eventOutput -> {
              var newTargetState = targetState.forward(List.of(eventOutput.getType()));
              if (newTargetState == null)
                return Mono.error(new IllegalStateException("No possible model for choice output " + eventOutput.getType().name()));
              return calculateChange(eventLog, join(newEvents, eventOutput), incomingNotification, idsForNewEntity);
            })
            .onErrorResume(
                t -> withCorrelationId(correlationId -> listener.changeFailed(
                    correlationId,
                    toListenerFormat(List.of(new Change(
                            eventLog.entityModel(),
                            entityId,
                            List.of(),
                            null,
                            null,
                            newEvents,
                            idsForNewEntity,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            newEvents.getLast().getTimestamp(),
                            correlationId
                        )
                    )),
                    t
                ))
                    .thenReturn(new ChangeResult(new ProcessResult(Status.Failed, entity, null, t.getMessage()), null))
            );
      }
      transitionEvents = newEvents;
      reverseTransitions = false;
      eventsToStore = newEvents;
    }
    // TODO: Nested changes are calculated _after_ this, so createData does not have access to "nested entities".
    return transitionsWithData(
        actualTransitions(
            startEventNumber,
            entity,
            effectiveEvents,
            eventsToStore,
            transitionEvents,
            reverseTransitions
        ),
        entity,
        effectiveEvents,
        eventsToStore,
        incomingNotification
    ).collectList()
        .flatMap(transitionsWithData -> Flux.fromIterable(transitionsWithData)
            .flatMap(actualTransition -> calculateNestedChanges(entity, actualTransition)).collectList()
            .flatMap(changeResultList -> {
              ProcessResult negativeResult = changeResultList.stream()
                  .map(ChangeResult::result)
                  .filter(result -> !result.isAccepted())
                  .findFirst()
                  .orElse(null);
              if (negativeResult != null)
                return Mono.just(new ChangeResult(new ProcessResult(
                    negativeResult.status(),
                    entity,
                    negativeResult.responseMessage(),
                    negativeResult.error()
                ), null));
              List<ProcessResult> processResults = changeResultList.stream()
                  .map(ChangeResult::result)
                  .toList();
              List<Event> processedEvents = changeResultList.stream()
                  .flatMap(changeResult -> changeResult.changes().stream())
                  .flatMap(change -> change.newEvents().stream())
                  .toList();
              List<Event> finalEventsToStore = addTransitionData(eventsToStore, transitionsWithData.stream().map(TransitionWithData::data).collect(Collectors.toList()));
              return secondaryIdsToAdd(transitionsWithData).collectList()
                  .flatMap(secondaryIdsToAdd -> correlationId().flatMap(correlationId -> Flux.fromIterable(transitionsWithData)
                          .flatMap(transition0 -> outgoingRequests(
                              transition0,
                              new Entity(entity.id(), join(entity.secondaryIds(), secondaryIdsToAdd), entity.model()),
                              finalEventsToStore,
                              effectiveEvents,
                              correlationId,
                              incomingNotification,
                              processResults,
                              processedEvents
                          )).collectList()
                          .zipWith(Flux.fromIterable(transitionsWithData)
                              .flatMap(transition0 -> outgoingResponses(
                                  transition0,
                                  new Entity(entity.id(), join(entity.secondaryIds(), secondaryIdsToAdd), entity.model()),
                                  finalEventsToStore,
                                  effectiveEvents,
                                  correlationId,
                                  incomingNotification,
                                  processResults,
                                  processedEvents
                              )).collectList())
                      )
                      .flatMap(outgoingNotifications -> correlationId().map(correlationId -> new ChangeResult(
                          new ProcessResult(
                              Status.Accepted,
                              new Entity(entity.id(), join(entity.secondaryIds(), secondaryIdsToAdd), entity.model()),
                              null,
                              null
                          ),
                          join(changeResultList.stream()
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
                              incomingNotification instanceof Notification.IncomingRequest ir ? List.of(ir) : List.of(),
                              outgoingNotifications.getT2(),
                              outgoingNotifications.getT1(),
                              incomingNotification instanceof Notification.IncomingResponse ir ? List.of(ir) : List.of(),
                              getDeadline(targetState.state()),
                              correlationId
                          ))
                      ))));

            }));
  }

  private List<Event> addTransitionData(List<Event> events, List<?> datas) {
    return events.stream()
        .map(event -> {
              if (event.getData() != null || datas.isEmpty())
                return event;
              Object data = datas.stream().filter(d -> event.getType().dataType() == d.getClass()).findFirst().orElse(null);
              if (data == null)
                return event;
              return new Event(
                  event.getEventNumber(),
                  event.getType(),
                  clock,
                  event.getMessageId(),
                  event.getClientId(),
                  data
              );
            }
        )
        .toList();
  }

  private <T> Mono<TransitionWithData<T>> transitionWithData(ActualTransition<T> transition, Entity entity, List<Event> eventLog, List<Event> newEvents, Notification incomingNotification) {
    return createData(transition.model(), entity, eventLog, newEvents, transition.event(), incomingNotification).map(data -> new TransitionWithData<>(transition, data));
  }

  private Flux<TransitionWithData<?>> transitionsWithData(List<ActualTransition<?>> transitions, Entity entity, List<Event> eventLog, List<Event> newEvents, Notification incomingNotification) {
    return Flux.fromIterable(transitions).flatMap(transition -> transitionWithData(transition, entity, eventLog, newEvents, incomingNotification));
  }

  private Mono<ProcessResult> processEvents(
      EventLog eventLog,
      List<Event> newEvents,
      Notification incomingNotification,
      List<SecondaryId> idsForNewEntity
  ) {
    return calculateChange(eventLog, newEvents, incomingNotification, idsForNewEntity)
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
                change.newEvents().stream().map(e -> new Listener.Change.Event(
                    e.getEventNumber(),
                        e.getType(),
                        e.getData()
                )).toList(),
                change.newSecondaryIds().stream().map(SecondaryId::toString).toList(),
                change.incomingRequests().stream().map(Notification::message).toList(),
                change.outgoingResponses().stream().map(Notification::message).toList(),
                change.outgoingRequests().stream().map(n -> n.subscriber().name() + "=>" + n.message()).toList(),
                change.incomingResponses().stream().map(n -> n.subscriber().name() + "=>" + n.message()).toList()
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
        .filter(change -> !change.newEvents().stream().allMatch(e -> e.getType().isReadOnly()))
        .toList();
    return correlationId() // TODO: Already have correlationId in _change_
        // TODO: Not really handling multiple changes at once
        .delayUntil(c -> changes.isEmpty() ?
            Mono.empty() :
            delayer.apply(changes.getLast().newEvents().stream().map(e -> c + "-" + e.getTypeName()).toList())
        )
        .flatMap(correlationId -> (changes.isEmpty() ? Flux.<ChangeState.OutboxElement>empty() : changeState.execute(changes))
            .collectList()
            // Forward outgoing requests (for guaranteed delivery this will be the first attempt)
            .map(outboxElementsToForward -> IntStream.range(
                    0,
                    (int) outboxElementsToForward.stream().filter(e -> e.changeIndex() == changes.size() - 1).count()
                )
                .boxed()
                .map(outboxElementsToForward::get)
                .map(q -> new OutboxElement(
                    q.elementId(),
                    q.outboxElementId(),
                    changes.getLast().entityId(),
                    changes.getLast().entityModel(),
                    changes.getLast().outgoingRequests().get(q.notificationIndex()).eventNumber(),
                    changes.getLast().outgoingRequests().get(q.notificationIndex()).subscriber(),
                    changes.getLast().outgoingRequests().get(q.notificationIndex()).guaranteed(),
                    changes.getLast().newEvents().getFirst().getTimestamp(),
                    changes.getLast().outgoingRequests().get(q.notificationIndex()).message(),
                    correlationId,
                    1,
                    null,
                    null
                )).toList())
            .doOnNext(queueElements -> queueElements.forEach(queueElement -> doForward(
                    queueElement
                ).doOnError(
                        TimeoutException.class,
                        _ -> listener.notificationResponseTimeout(
                            correlationId,
                            unfilteredChanges.getFirst().entityId(),
                            queueElement.eventNumber(),
                            queueElement.subscriber().name()
                        )
                    )
                    .doOnError(Throwable::printStackTrace)
                    .contextWrite(Correlation.correlationIdContext(correlationId))
                    .subscribe()
            ))
            .thenReturn(new ProcessResult(
                Status.Accepted,
                new Entity(unfilteredChanges.getLast().entityId(), unfilteredChanges.getLast().secondaryIds(), unfilteredChanges.getLast().entityModel()),
                unfilteredChanges.getLast().outgoingResponses().stream().map(OutgoingResponse::message).findFirst().orElse(null),
                null
            ))
            .onErrorResume(error -> handleTransitionError(changes, error, eventLog))
        );
  }

  Mono<ForwardStatus> doForward(OutboxElement queueElement) {
    String message;
    try {
      message = ofNullable(queueElement.subscriber().reattemptTransformation()).map(queueElement::data).orElse(queueElement.data());
    } catch (Exception e) {
      String reason = "Reattempt transformation failed: " + e.getMessage();
      return moveToDLQ.execute(queueElement, reason)
              .doOnSuccess(_ -> logDead(queueElement, reason))
              .thenReturn(ForwardStatus.Dead);
    }
    HttpRequestMessage httpRequest;
    try {
      httpRequest = HttpMessageParser.parseRequest(message);
    } catch (Exception e) {
      String reason = "Parsing request message failed: " + e.getMessage();
      return moveToDLQ.execute(queueElement, reason)
          .doOnSuccess(_ -> logDead(queueElement, reason))
          .thenReturn(ForwardStatus.Dead);
    }
    return queueElement.subscriber().client().exchange(httpRequest)
        .map(httpResponse -> queueElement.subscriber().responseEvaluator().evaluate(httpRequest, httpResponse))
        .flatMap(evaluatedResponse -> handleResponse(queueElement, evaluatedResponse))
            .onErrorResume(TimeoutException.class, _ -> backOffOrDie(queueElement, "Client timeout"))
            .onErrorResume(e -> backOffOrDie(queueElement, reason(e)))
            .contextWrite(Correlation.correlationIdContext(queueElement.correlationId()));
  }

  private Mono<ForwardStatus> handleResponse(OutboxElement queueElement, EvaluatedResponse evaluatedResponse) {
    if (!queueElement.guaranteed()) {
      return doProcessIncomingResponse(1, queueElement.entityModel(), queueElement.entityId(), responseNotification(queueElement, evaluatedResponse.message()))
              .map(_ -> ForwardStatus.Ok);
    }
    return switch (evaluatedResponse.status()) {
      case Ok -> doProcessIncomingResponse(1, queueElement.entityModel(), queueElement.entityId(), responseNotification(queueElement, evaluatedResponse.message()))
              .filter(processResult -> processResult.isAccepted() || processResult.isRepeated())
              .map(_ -> ForwardStatus.Ok)
              .switchIfEmpty(
                      dequeueAndStoreReceipt.execute(queueElement, evaluatedResponse.message(), ZonedDateTime.now(clock))
                              .thenReturn(ForwardStatus.Ok)
                              .doOnSuccess(_ -> logForwarded(
                                      queueElement,
                                      evaluatedResponse.message(),
                                      evaluatedResponse.statusReason().message()
                              ))
              );
      case PermanentError -> moveToDLQ.execute(queueElement, evaluatedResponse.statusReason().message())
              .doOnSuccess(_ -> logDead(queueElement, evaluatedResponse.statusReason().message()))
              .thenReturn(ForwardStatus.Dead);
      case TransientError -> backOffOrDie(queueElement, evaluatedResponse.statusReason().message());
    };
  }

  private Notification.IncomingResponse responseNotification(OutboxElement queueElement, String responseMessage) {
    return new Notification.IncomingResponse(
        // Synchronous response will always trigger an event following directly the request event
        queueElement.eventNumber() + 1,
        responseMessage,
        queueElement.outboxElementId(),
        queueElement.subscriber(),
        queueElement.guaranteed()
    );
  }

  // TODO
  private final DelaySpecification backoff = new DelaySpecification(ofSeconds(10), ofMinutes(10), ofHours(5), 1.5);

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
    listener.forwardingDeadByExhaustion(e.entityId(), e.subscriber().name(), e.enqueuedAt(), e.attempt(), e.eventNumber(), e.correlationId(), reason);
  }

  private void logForwarded(OutboxElement e, String responseMessage, String reason) {
    listener.forwardingCompleted(e.subscriber().name(), e.enqueuedAt(), e.attempt(), e.entityId(), e.eventNumber(), e.correlationId(), responseMessage, reason);
  }

  private void logBackoff(OutboxElement e, String reason) {
    // TODO: e.backoff() requires processedAt nextAttemptAt
    listener.forwardingBackedOff(e.subscriber().name(), e.enqueuedAt(), e.attempt(), e.entityId(), e.eventNumber(), e.correlationId(), reason, e.nextAttemptAt(), e.backoff());
  }

  private String reason(Throwable t) {
    try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
      t.printStackTrace(pw);
      return sw.toString();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void logDead(OutboxElement e, String reason) {
    listener.forwardingDead(e.entityId(), e.subscriber().name(), e.enqueuedAt(), e.attempt(), e.eventNumber(), e.correlationId(), reason);
  }

  private <T> Flux<Notification.OutgoingRequest> outgoingRequests(
      TransitionWithData<T> transition,
      Entity entity,
      List<Event> newEvents,
      List<Event> eventLog,
      String correlationId,
      Notification incomingNotification,
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
            incomingNotification,
            processResults,
            processedEvents
        ));
  }

  private <T> Flux<Notification.OutgoingResponse> outgoingResponses(
      TransitionWithData<T> transition,
      Entity entity,
      List<Event> newEvents,
      List<Event> eventLog,
      String correlationId,
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
            correlationId,
            incomingNotification,
            processResults,
            processedEvents
        ));
  }

  private record TransitionWithData<T>(ActualTransition<T> transition, T data) {}

  private Flux<SecondaryId> secondaryIdsToAdd(
      List<TransitionWithData<?>> transitionsWithData
  ) {
    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
    for (var actualTransition : transitionsWithData) {
      secondaryIdFlux = secondaryIdFlux.mergeWith(secondaryIdsToAdd(actualTransition));
    }
    return secondaryIdFlux;
  }

  private <T> Flux<SecondaryId> secondaryIdsToAdd(TransitionWithData<T> transitionWithData) {
    Flux<SecondaryId> secondaryIdFlux = Flux.empty();
    secondaryIdFlux = secondaryIdFlux.mergeWith(Flux
        .fromIterable(transitionWithData.transition().model().newIdentifiers())
        .map(newId -> newId.apply(transitionWithData.data())));
    return secondaryIdFlux;
  }

  private <T, U> Mono<Notification.OutgoingRequest> createOutgoingRequestNotification(
      TransitionWithData<T> transition,
      Entity entity,
      Event currentEvent,
      List<Event> newEvents,
      List<Event> eventLog,
      OutgoingRequestModel<T, U> notificationModel,
      String correlationId,
      Notification incomingNotification,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    OutgoingRequestCreator<U> creator = notificationModel.notificationCreatorType() != null ?
        beanRegistry.getBean(notificationModel.notificationCreatorType()) :
        notificationModel.notificationCreator();
    var filteredEvents = new RequiredData(
        entity,
        join(eventLog, newEvents),
        currentEvent,
        newEvents.getLast(),
        processResults,
        processedEvents,
        incomingNotification == null ? List.of() : List.of(incomingNotification),
        creator.requirements(),
        notificationModel.notificationCreatorType() != null ? notificationModel.notificationCreatorType() : notificationModel.notificationCreator().getClass(),
        incomingRequestByEvent,
        outgoingRequestByEvent,
        incomingResponseByEvent
    );
    Entity parentEntity = filteredEvents.nestedEntities().stream()
        .filter(nestedEntity -> entity.model().equals(nestedEntity.model().childEntity()))
        .findFirst().orElse(null);
    return creator.create(
            notificationModel.dataAdapter().apply(transition.data()),
            entity.id(),
            currentEvent.getEventNumber(),
            notificationModel,
            correlationId,
            filteredEvents
        )
        .map(message -> new Notification.OutgoingRequest(
            currentEvent.getEventNumber(),
            message,
            notificationModel.subscriber(),
            notificationModel.guaranteed(),
            parentEntity != null ? parentEntity.id() : null
        ));
  }

  private <T, U> Mono<Notification.OutgoingResponse> createOutgoingResponseNotification(
      TransitionWithData<T> transition,
      Entity entity,
      Event currentEvent,
      List<Event> newEvents,
      List<Event> eventLog,
      OutgoingResponseModel<T, U> notificationModel,
      String correlationId,
      Notification incomingNotification,
      List<ProcessResult> processResults,
      List<Event> processedEvents
  ) {
    OutgoingResponseCreator<U> creator = notificationModel.creatorType() != null ?
        beanRegistry.getBean(notificationModel.creatorType()) :
        notificationModel.creator();
    var filteredEvents = new RequiredData(
        entity,
        join(eventLog, newEvents),
        currentEvent,
        newEvents.getLast(),
        processResults,
        processedEvents,
        incomingNotification == null ? List.of() : List.of(incomingNotification),
        creator.requirements(),
        notificationModel.creatorType() != null ? notificationModel.creatorType() : notificationModel.creator().getClass(),
        incomingRequestByEvent,
        outgoingRequestByEvent,
        incomingResponseByEvent
    );
    // TODO: Fix correlation with request (or not)?
    return creator.create(
        notificationModel.dataAdapter().apply(transition.data()),
        incomingNotification instanceof Notification.IncomingRequest rq ? rq : null,
        entity.id(),
        correlationId,
        filteredEvents
    ).map(message -> new Notification.OutgoingResponse(currentEvent.getEventNumber(), message, incomingNotification instanceof Notification.IncomingRequest rq ? rq.eventNumber() : -1));
  }

  private ZonedDateTime getDeadline(State targetState) {
    return targetState.timeout().map(timeout -> ZonedDateTime.now(clock).plus(timeout.duration())).orElse(null);
  }

  private Mono<Event> executeAction(
      Entity entity,
      EventLog eventLog,
      Action action,
      State currentState,
      List<Notification> notifications
  ) {
    var lastEvent = eventLog.events().getLast();
    return action.execute(eventLog.entityId(), new RequiredData(
            entity,
            eventLog.effectiveEvents(),
            lastEvent,
            lastEvent,
            List.of(),
            List.of(),
            notifications,
            action.requirements(),
            action.getClass(),
            incomingRequestByEvent,
            outgoingRequestByEvent,
            incomingResponseByEvent
        ))
        .flatMap(output -> withCorrelationId(correlationId -> listener
            .actionExecuted(
                correlationId,
                eventLog.entityId(),
                action.getClass().getSimpleName(),
                currentState.toString(),
                output
            )
        ).thenReturn(output));
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
      return outgoingResponseByRequest.execute(eventLog.entityModel(), incomingRequest.messageId(), incomingRequest.clientId())
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
                  incomingRequest.clientId(),
                  "C" + incomingRequest.messageId(),
                  incomingRequest.message(),
                  incomingRequest.digest(),
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
                  List.of(new Event(event0.getEventNumber(), BuiltinEventTypes.InconsistentState, clock, e.getMessage())),
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

  private record ActualTransition<T>(
      TransitionModel<T> model,
      Event event
  ) {}

  /*
   * Get the list of transitions and their events from event log starting from startEventNumber.
   */
  private List<ActualTransition<?>> actualTransitions(
      int startEventNumber,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      List<Event> transitionEvents,
      boolean reverse
  ) {
    TraversableState state = entity.model().begin();
    // Skip till startEventNumber
    for (var event : eventLog) {
      if (event.getEventNumber() <= startEventNumber) {
        state = state.forward(event.getType());
      }
    }
    List<ActualTransition<?>> list = new ArrayList<>();
    for (var event : transitionEvents) {
      TransitionModel<?> transition = requireNonNull(state.transition(event.getType()), "Transition for " + event.getType() + " from " + state.state() + " not allowed");
      if (reverse && !newEvents.contains(event)) // New events never trigger reverse transitions
        transition = transition.reverse();
      list.add(new ActualTransition<>(transition, event));
      state = state.forward(transition.eventType());
    }
    return list;
  }

  private <T> Mono<T> createData(
      TransitionModel<T> transitionModel,
      Entity entity,
      List<Event> eventLog,
      List<Event> newEvents,
      Event transitionEvent,
      Notification incomingNotification
  ) {
    DataCreator<T> dataCreator = transitionModel.dataCreator();
    if (dataCreator == null && transitionModel.dataCreatorType() != null)
      dataCreator = beanRegistry.getBean(transitionModel.dataCreatorType());
    if (dataCreator != null) {
      RequiredData requiredData = requiredData(dataCreator, entity, eventLog, newEvents, transitionEvent, incomingNotification);
      return dataCreator.execute(requiredData);
    }
    return Mono.empty();
  }

  private List<EventType> scheduledEvents(Entity entity, List<Event> eventLog, ZonedDateTime deadline) {
    var scheduledEvents = new ArrayList<EventType>();
    for (var actualTransition : actualTransitions(0, entity, eventLog, List.of(), eventLog, false)) {
      scheduledEvents.addAll(
          actualTransition.model().scheduledEvents().stream()
              .filter(se -> !actualTransition.event().getTimestamp().plus(se.deadline()).isAfter(deadline))
              .map(ScheduledEvent::type)
              .toList()
      );
    }
    return unmodifiableList(scheduledEvents);
  }

  private boolean isRolledBack(EventLog eventLog, String clientId, String messageId) {
    return eventLog.events().stream()
        .filter(e -> e.getType().isRollback())
        .anyMatch(e -> Objects.equals(clientId, e.getClientId()) && Objects.equals(messageId, e.getMessageId()));
  }

  private boolean isPendingIncomingResponse(EventLog eventLog) {
    TransitionModel<?> lastTransition = lastTransition(eventLog);
    return lastTransition != null && lastTransition.outgoingRequests()
        .stream().anyMatch(s -> s.responseValidator() != null);
  }

  private TransitionModel<?> lastTransition(EventLog eventLog) {
    var effectiveEvents = eventLog.effectiveEvents();
    if (effectiveEvents.isEmpty()) return null;
    var nextToLastState = eventLog.entityModel().begin().forward(effectiveEvents.subList(0, effectiveEvents.size()-1).stream().map(Event::getType).toList());
    return requireNonNull(nextToLastState).transition(effectiveEvents.getLast().getType());
  }

  private TraversableState traverseTo(EntityModel entityModel, List<Event> eventLog, int eventNumber) {
    if (eventNumber == 0)
      return entityModel.begin();
    var state = entityModel.begin();
    for (var event : eventLog) {
      state = state.forward(event.getType());
      if (event.getEventNumber() == eventNumber)
        return state;
    }
    throw new IllegalArgumentException("No event with number " + eventNumber);
  }

}
