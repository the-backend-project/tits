package com.github.thxmasj.statemachine;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.github.thxmasj.statemachine.Notification.OutgoingResponse;
import com.github.thxmasj.statemachine.OutboxWorker.ExchangeType;
import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.Requirements.Requirement;
import com.github.thxmasj.statemachine.Requirements.Type;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.IncomingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public class RequiredData implements Input {

  private final int nextEventNumber;
  private final Class<?> requirer;
  private final Requirements requirements;
  private final Map<Requirement, List<Event>> filteredEvents = new HashMap<>();
  private final List<Event> events;
  private final Event currentEvent;
  private final Event triggerEvent;
  private final Entity entity;
  private final List<Notification> inflightNotifications;
  private final List<ProcessResult> processResults;
  private final List<Event> processedEvents;
  private final IncomingRequestByEvent incomingRequestByEvent;
  private final OutgoingRequestByEvent outgoingRequestByEvent;

  public static class RequirementsNotFulfilled extends RuntimeException {

    public RequirementsNotFulfilled(String message) {
      super(message);
    }
  }

  public static class GetNotificationFailed extends RequirementsNotFulfilled {

    private GetNotificationFailed(String message) {
      super(message);
    }
  }

  private boolean isMatchingRequirement(Notification notification, ExchangeType exchangeType, OutboxQueue queue) {
    return switch (notification) {
      case Notification.IncomingRequest _ -> exchangeType == ExchangeType.IncomingRequest;
      case Notification.OutgoingRequest outgoingRequest -> exchangeType == ExchangeType.OutgoingRequest && queue.equals(outgoingRequest.queue());
      case Notification.IncomingResponse incomingResponse -> exchangeType == ExchangeType.IncomingResponse && queue.equals(incomingResponse.queue());
      case OutgoingResponse _ -> exchangeType == ExchangeType.OutgoingResponse;
    };
  }

  private Mono<HttpRequestMessage> getNotification(
      EntityId entityId,
      EntityModel entityModel,
      Event event,
      ExchangeType exchangeType,
      OutboxQueue queue
  ) {
    //noinspection SwitchStatementWithTooFewBranches
    return inflightNotifications.stream()
        .filter(n -> n != null &&
            n.eventNumber() == event.eventNumber() &&
            isMatchingRequirement(n, exchangeType, queue)
        )
        .findFirst()
        .map(n -> switch (n) {
          case Notification.IncomingRequest rq -> Mono.just(rq.message());
          default -> throw new IllegalStateException("Unexpected value: " + n);
        })
        .orElse(switch (exchangeType) {
          case ExchangeType.IncomingRequest ->
              incomingRequestByEvent.execute(entityModel, entityId, event.eventNumber());
          case ExchangeType.OutgoingRequest -> outgoingRequestByEvent.execute(entityId, event.eventNumber());
          default -> Mono.error(
              new GetNotificationFailed(format(
                  "No %s notification found for event %s with number %s of type %s and queue %s for requirer %s. Inflight notifications: %s. Events: %s",
                  exchangeType,
                  event.type(),
                  event.eventNumber(),
                  exchangeType,
                  queue,
                  requirer.getName(),
                  inflightNotifications,
                  events
              )));
        });
  }

  public RequiredData(
      Entity entity,
      List<Event> events,
      Event currentEvent,
      Event triggerEvent,
      List<ProcessResult> processResults,
      List<Event> processedEvents,
      List<Notification> inflightNotifications,
      Requirements requirements,
      Class<?> requirer,
      IncomingRequestByEvent incomingRequestByEvent,
      OutgoingRequestByEvent outgoingRequestByEvent
  ) {
    this.requirer = requireNonNull(requirer);
    this.requirements = requirements;
    this.events = events;
    this.currentEvent = currentEvent;
    this.triggerEvent = triggerEvent;
    this.entity = entity;
    this.inflightNotifications = inflightNotifications;
    this.processResults = processResults;
    this.processedEvents = processedEvents;
    this.incomingRequestByEvent = incomingRequestByEvent;
    this.outgoingRequestByEvent = outgoingRequestByEvent;
    for (var requirement : requirements.asList()) {
      FilterResult result = filter(requirement, events);
      if (result.events() != null) {
        filteredEvents.put(requirement, result.events());
      }
    }
    this.nextEventNumber = !events.isEmpty() ? events.getLast().eventNumber() + 1 : 1;
  }

  @Override
  public int nextEventNumber() {
    return nextEventNumber;
  }

  @Override
  public <T> Mono<IncomingRequest> incomingRequest(EventType eventType, Class<T> type) {
    final var event = last(eventType);
    return getNotification(entity.id(), entity.model(), event, ExchangeType.IncomingRequest, null)
        .map(message -> new IncomingRequest(
                message,
                event.messageId(),
                event.clientId(),
                event.eventNumber()
            )
        );
  }

  @Override
  public <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return getNotification(entity.id(), entity.model(), loadedEvent, ExchangeType.OutgoingRequest, null)
        .map(message -> new OutgoingRequest<>(
                message,
                loadedEvent.eventNumber()
            )
        );
  }

  @Override
  public final List<Event> all(EventType... eventTypes) {
    return filteredForRequirement(Type.All, requirements.on(eventTypes));
  }

  @Override
  public Event one(EventType eventType) {
    return filteredForSingletonRequirement(Type.One, requirements.on(eventType));
  }

  @Override
  public final Event one(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.One, requirements.on(eligibleEventTypes));
  }

  @Override
  public Optional<Event> oneIfExists(EventType eventType) {
    return filteredForOptionalRequirement(Type.OneIfExists, requirements.on(eventType));
  }

  @Override
  public Event current(EventType eligibleEventType) {
    return filteredForSingletonRequirement(Type.Current, requirements.on(eligibleEventType));
  }

  @Override
  public final Event current(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.Current, requirements.on(eligibleEventTypes));
  }

  @Override
  public final Event trigger(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.Trigger, requirements.on(eligibleEventTypes));
  }

  @Override
  public final Optional<Event> lastIfExists(EventType... eligibleEventTypes) {
    return filteredForOptionalRequirement(Type.LastIfExists, requirements.on(eligibleEventTypes));
  }

  @Override
  public Event last(EventType eventType) {
    var matchingRequirements = requirements.on(eventType);
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(" + eventType + "): No requirements found for " + eventType);
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public Event last() {
    var matchingRequirements = requirements.on();
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(): No requirements found");
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public Event last(Class<?> dataType) {
    var matchingRequirements = requirements.on();
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(): No requirements found");
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public Optional<Event> firstIfExists(EventType eventType) {
    return filteredForOptionalRequirement(Type.FirstIfExists, requirements.on(eventType));
  }

  @Override
  public Entity entity() {
    return entity;
  }

  @Override
  public List<Entity> nestedEntities() {
    //noinspection SimplifyStreamApiCallChains
    return processResults.stream().filter(r -> r.entity() != null).map(ProcessResult::entity).toList();
  }

  @Override
  public Entity nestedEntity(String entityName) {
    return nestedEntities().stream()
        .filter(entity -> entity.model().name().equals(entityName))
        .findFirst()
        .orElse(null);
  }

  @Override
  public SecondaryId secondaryId(String entityName, SecondaryIdModel idModel) {
    var entity = nestedEntity(entityName);
    if (entity == null)
      throw new RequirementsNotFulfilled("No nested entity " + entityName);
    return entity.secondaryIds().stream()
        .filter(sid -> sid.model() == idModel)
        .findFirst()
        .orElseThrow(() -> new RequirementsNotFulfilled("No secondary id " + entityName + "/" + idModel.name()));
  }

  @Override
  public ProcessResult processResult(EntityModel entityType, EntityId entityId) {
    return processResults.stream().filter(r -> r.entity().model() == entityType && r.entity().id().equals(entityId)).findFirst().orElseThrow();
  }

  @Override
  public Event processedEvent(EventType eventType) {
    return processedEvents.stream().filter(e -> e.type() == eventType).findFirst().orElseThrow();
  }

  @Override
  public ZonedDateTime timestamp() {
    return events.isEmpty() ? null : events.getFirst().timestamp();
  }

  private List<Event> filteredForRequirement(Type expectedType, List<Requirement> requirements) {
    Requirement requirement = requirements
        .stream()
        .filter(r -> r.type() == expectedType)
        .findFirst()
        .orElseThrow(() ->
            new MissingRequirement(format(
                "%s requires %s which can't be found: %s",
                requirer.getName(),
                expectedType,
                requirements
            ))
        );
    var result = filteredEvents.get(requirement);
    if (result == null)
      throw new MissingRequirement(format(
          "%s requests result for a requirement which was not specified: %s. Available requirements are: %s",
          requirer.getName(),
          requirement,
          filteredEvents
      ));
    return result;
  }

  private Optional<Event> filteredForOptionalRequirement(
      Type expectedType,
      List<Requirement> requirements
  ) {
    var result = filteredForRequirement(expectedType, requirements);
    if (result.size() > 1)
      throw new IllegalStateException(
          "More than one requirement matches " + expectedType + " for given event type: " + result.size());
    return Optional.ofNullable(result.size() == 1 ? result.getFirst() : null);
  }

  private Event filteredForSingletonRequirement(
      Type expectedType,
      List<Requirement> requirements
  ) {
    List<Event> result = filteredForRequirement(expectedType, requirements);
    if (result.size() != 1)
      throw new IllegalStateException(
          "Not exactly one event matches " + expectedType + " for given requirements\nEvents:\n" +
              result.stream().map(Event::toString).collect(joining("\n")) + "Requirements:\n" +
              requirements.stream().map(Object::toString).collect(joining("\n"))
      );
    return result.getFirst();
  }

  private record FilterResult(List<Event> events, String error) {

    private FilterResult(List<Event> events) {
      this(events,null);
    }

    private FilterResult(String error) {
      this(null, error);
    }
  }

  private FilterResult filter(Requirement requirement, List<Event> events) {
    return switch (requirement.type()) {
      case All -> new FilterResult(filter(requirement.eventTypes(), events).collect(toList()));
      case One -> filter(requirement.eventTypes(), events).collect(collectingAndThen(toList(), l -> l.size() != 1
              ? new FilterResult(format(
              "Required exactly one event of types %s but found %d",
              formatEventTypes(requirement),
              l.size()
          ))
              : new FilterResult(l)
      ));
      case OneIfExists -> filter(requirement.eventTypes(), events).collect(collectingAndThen(toList(), l -> l.size() > 1
              ? new FilterResult(format(
              "Required zero or one event of types %s but found %d",
              formatEventTypes(requirement),
              l.size()
          ))
              : new FilterResult(l)
      ));
      case FirstIfExists ->
          filter(requirement.eventTypes(), events).collect(collectingAndThen(toList(), l -> !l.isEmpty()
              ? new FilterResult(List.of(l.getFirst()))
              : new FilterResult(l)
          ));
      case Last -> filter(requirement.eventTypes(), events).collect(collectingAndThen(toList(), l -> l.isEmpty()
              ? new FilterResult(format(
              "Required at least one event of type %s but found 0",
              formatEventTypes(requirement)
          ))
              : new FilterResult(List.of(l.getLast()))
      ));
      case LastIfExists ->
          filter(requirement.eventTypes(), events).collect(collectingAndThen(toList(), l -> !l.isEmpty()
              ? new FilterResult(List.of(l.getLast()))
              : new FilterResult(l)
          ));
      case Current -> Optional.of(currentEvent)
          .filter(event -> requirement.eventTypes().contains(event.type()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(
              format("Required at least one event of type %s but found 0", formatEventTypes(requirement))));
      case CurrentIfExists -> Optional.of(currentEvent)
          .filter(event -> requirement.eventTypes().contains(event.type()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(List.of()));
      case Trigger -> Optional.of(triggerEvent)
          .filter(event -> requirement.eventTypes().contains(event.type()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(
              format("Required trigger event to be of type %s but was %s", formatEventTypes(requirement),
                  triggerEvent.type()
              )));
    };
  }

  private static  String formatEventTypes(Requirement requirement) {
    return requirement.eventTypes().stream().map(EventType::toString).collect(joining(","));
  }

  private static  Stream<Event> filter(
      List<EventType> types,
      List<Event> events
  ) {
    return types.isEmpty() ?
        events.stream() :
        events.stream().filter(e -> types.contains(e.type()));
  }

}
