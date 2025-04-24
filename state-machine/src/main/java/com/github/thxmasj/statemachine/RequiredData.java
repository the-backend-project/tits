package com.github.thxmasj.statemachine;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.thxmasj.statemachine.Event.LoadedEvent;
import com.github.thxmasj.statemachine.OutboxWorker.ExchangeType;
import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.Requirements.NotificationRequirement;
import com.github.thxmasj.statemachine.Requirements.Requirement;
import com.github.thxmasj.statemachine.Requirements.Type;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.IncomingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.IncomingResponseByEvent;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpResponseMessage;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import reactor.core.publisher.Mono;

public class RequiredData implements Input {

  private record RequirementKey(List<EventType> eventTypes, Type type) {

    private static  RequirementKey from(Requirement requirement) {
      return new RequirementKey(requirement.eventTypes(), requirement.type());
    }
  }

  private final int nextEventNumber;
  private final Class<?> requirer;
  private final Requirements requirements;
  private final Map<RequirementKey, List<LoadedEvent>> filteredEvents = new HashMap<>();
  private final List<Event> events;
  private final Event currentEvent;
  private final Event triggerEvent;
  private final Entity entity;
  private final List<ProcessResult> processResults;
  private final List<Event> processedEvents;
  private final List<String> errors = new ArrayList<>();
  private final IncomingRequestByEvent incomingRequestByEvent;
  private final OutgoingRequestByEvent outgoingRequestByEvent;
  private final IncomingResponseByEvent incomingResponseByEvent;

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

  private boolean isMatchingRequirement(Notification notification, NotificationRequirement requirement) {
    return switch (notification) {
      case Notification.IncomingRequest _ -> requirement.exchangeType() == ExchangeType.IncomingRequest;
      case Notification.OutgoingRequest outgoingRequest -> requirement.exchangeType() == ExchangeType.OutgoingRequest && requirement.subscriber().equals(outgoingRequest.subscriber());
      case Notification.IncomingResponse incomingResponse -> requirement.exchangeType() == ExchangeType.IncomingResponse && requirement.subscriber().equals(incomingResponse.subscriber());
      case Notification.OutgoingResponse _ -> requirement.exchangeType() == ExchangeType.OutgoingResponse;
    };
  }

  private Mono<String> getNotification(
      EntityId entityId,
      EntityModel entityModel,
      List<Notification> inflightNotifications,
      Event event,
      Requirement requirement
  ) {
    return inflightNotifications.stream()
        .filter(n -> n != null &&
            n.eventNumber() == event.getEventNumber() &&
            isMatchingRequirement(n, requirement.notification())
        )
        .findFirst()
        .map(Notification::message)
        .map(Mono::just)
        .orElse(switch (requirement.notification().exchangeType()) {
          case ExchangeType.IncomingRequest ->
              incomingRequestByEvent.execute(entityModel, entityId, event.getEventNumber());
          case ExchangeType.OutgoingRequest ->
              outgoingRequestByEvent.execute(entityModel, requirement.notification().subscriber(), entityId, event.getEventNumber());
          case ExchangeType.IncomingResponse ->
              incomingResponseByEvent.execute(entityModel, requirement.notification().subscriber(), entityId, event.getEventNumber());
          default -> Mono.error(
              new GetNotificationFailed(format(
                  "No %s notification found for event %s with number %s of type %s and subscriber %s for requirer %s. Inflight notifications: %s. Events: %s",
                  requirement.notification().exchangeType(),
                  event.getType(),
                  event.getEventNumber(),
                  requirement.notification().exchangeType(),
                  requirement.notification().subscriber(),
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
      OutgoingRequestByEvent outgoingRequestByEvent,
      IncomingResponseByEvent incomingResponseByEvent
  ) {
    this.requirer = requireNonNull(requirer);
    this.requirements = requirements;
    this.events = events;
    this.currentEvent = currentEvent;
    this.triggerEvent = triggerEvent;
    this.entity = entity;
    this.processResults = processResults;
    this.processedEvents = processedEvents;
    this.incomingRequestByEvent = incomingRequestByEvent;
    this.outgoingRequestByEvent = outgoingRequestByEvent;
    this.incomingResponseByEvent = incomingResponseByEvent;
      for (var requirement : requirements.asList()) {
      FilterResult result = filter(requirement, events);
      if (result.error() != null) {
        errors.add(result.error());
      } else if (result.events() != null) {
        try {
          List<LoadedEvent> loaded = result.events().stream().map(event ->
              loadEvent(
                  entity.id(),
                  entity.model(),
                  requirement,
                  event,
                  inflightNotifications
              )
          ).collect(toList());
          filteredEvents.put(RequirementKey.from(requirement), loaded);
        } catch (LoadFailed e) {
          errors.add(e.getMessage());
        }
      }
      if (!errors.isEmpty()) {
        throw new RequirementsNotFulfilled(format(
            "Requirement %s not fulfilled for requirer %s: %s\nCurrent event: %s\nTrigger event: %s\nEvents: %s",
            requirement,
            requirer.getName(),
            String.join(",", errors),
            currentEvent != null ? currentEvent.getType() : "N/A",
            triggerEvent != null ? triggerEvent.getType() : "N/A",
            events.stream().map(Event::getType).map(EventType::toString).collect(joining(","))
        ));
      }
    }
    this.nextEventNumber = !events.isEmpty() ? events.getLast().getEventNumber() + 1 : 1;
  }

  static class LoadFailed extends RuntimeException {

    LoadFailed(String message) {
      super(message);
    }
  }

  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false)
      .setSerializationInclusion(Include.NON_NULL);

  private LoadedEvent loadEvent(
      EntityId entityId,
      EntityModel entityModel,
      Requirement requirement,
      Event event,
      List<Notification> inflightNotifications
  ) throws LoadFailed {
    Object unmarshalledData;
    try {
      unmarshalledData = event.getUnmarshalledData();
    } catch (RuntimeException e) {
      throw new LoadFailed("Failed to unmarshal data from " + event.getType() + " -- data=" + event.getData() + ", error=" + e);
    }
    Mono<String> notification = requirement.notification() == null ? null : getNotification(entityId, entityModel, inflightNotifications, event, requirement);
    Mono<Object> loaded = null;
    if (notification != null) {
      loaded = notification.mapNotNull(n -> {
        String body = new HttpRequestMessage(n).body();
        if (requirement.notification().dataType() == String.class) {
          if (body == null) return ""; // TODO: Hacky way to handle incoming requests/responses without body.
          return (Object) body;
        } else if (body == null)
          return null;
        try {
          return objectMapper.readerFor(requirement.notification().dataType()).readValue(body);
        } catch (Exception e) {
          throw new RuntimeException(format(
              "Requirement %s: Failed to unmarshal message for notification from/to subscriber %s to type %s for event %s",
              requirement,
              requirement.notification().subscriber(),
              requirement.notification().dataType(),
              event.getType().name()
          ), e);
        }
      }).switchIfEmpty(Mono.error(new RequirementsNotFulfilled(format(
          "Requirement %s for: Failed to unmarshal message for notification from/to subscriber %s to type %s for event %s: Notification is null",
          requirement,
          requirement.notification().subscriber(),
          requirement.notification().dataType(),
          event.getType().name()
      ))));
    }
    return new LoadedEvent(
        unmarshalledData,
        loaded,
        event,
        notification
    );
  }

  public boolean failed() {
    return !errors.isEmpty();
  }

  @Override
  public int nextEventNumber() {
    return nextEventNumber;
  }

  @Override
  public <T> Mono<IncomingRequest> incomingRequest(EventType eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return loadedEvent.getNotification()
        .map(message -> new IncomingRequest(
                new HttpRequestMessage(message),
                loadedEvent.getMessageId(),
                loadedEvent.clientId(),
                loadedEvent.eventNumber()
            )
        );
  }

  @Override
  public <T> Mono<OutgoingRequest<T>> outgoingRequest(Subscriber subscriber, EventType eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return loadedEvent.getLoadedData(type).zipWith(loadedEvent.getNotification())
        .map(parsedRequestAndMessage -> new OutgoingRequest<>(
            parsedRequestAndMessage.getT2(),
            parsedRequestAndMessage.getT1(),
            loadedEvent.eventNumber()
        ));
  }

  @Override
  public <T> Mono<IncomingResponse> incomingResponse(Subscriber subscriber, EventType eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return loadedEvent.getNotification()
        .map(message -> new IncomingResponse(
            new HttpResponseMessage(message),
            loadedEvent.eventNumber()
        ));
  }

  @Override
  public final List<LoadedEvent> all(EventType... eventTypes) {
    return filteredForRequirement(Type.All, requirements.on(eventTypes));
  }

  @Override
  public LoadedEvent one(EventType eventType) {
    return filteredForSingletonRequirement(Type.One, requirements.on(eventType));
  }

  @Override
  public final LoadedEvent one(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.One, requirements.on(eligibleEventTypes));
  }

  @Override
  public Optional<LoadedEvent> oneIfExists(EventType eventType) {
    return filteredForOptionalRequirement(Type.OneIfExists, requirements.on(eventType));
  }

  @Override
  public LoadedEvent current(EventType eligibleEventType) {
    return filteredForSingletonRequirement(Type.Current, requirements.on(eligibleEventType));
  }

  @Override
  public final LoadedEvent current(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.Current, requirements.on(eligibleEventTypes));
  }

  @Override
  public final LoadedEvent trigger(EventType... eligibleEventTypes) {
    return filteredForSingletonRequirement(Type.Trigger, requirements.on(eligibleEventTypes));
  }

  @Override
  public final Optional<LoadedEvent> lastIfExists(EventType... eligibleEventTypes) {
    return filteredForOptionalRequirement(Type.LastIfExists, requirements.on(eligibleEventTypes));
  }

  @Override
  public LoadedEvent last(EventType eventType) {
    var matchingRequirements = requirements.on(eventType);
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(" + eventType + "): No requirements found for " + eventType);
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public LoadedEvent last() {
    var matchingRequirements = requirements.on();
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(): No requirements found");
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public LoadedEvent last(Class<?> dataType) {
    var matchingRequirements = requirements.on();
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(): No requirements found");
    }
    return filteredForSingletonRequirement(Type.Last, matchingRequirements);
  }

  @Override
  public Optional<LoadedEvent> firstIfExists(EventType eventType) {
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
  public ProcessResult processResult(EntityModel entityType, EntityId entityId) {
    return processResults.stream().filter(r -> r.entity().model() == entityType && r.entity().id().equals(entityId)).findFirst().orElseThrow();
  }

  @Override
  public Event processedEvent(EventType eventType) {
    return processedEvents.stream().filter(e -> e.getType() == eventType).findFirst().orElseThrow();
  }

  @Override
  public ZonedDateTime timestamp() {
    return events.isEmpty() ? null : events.getFirst().getTimestamp();
  }

  private List<LoadedEvent> filteredForRequirement(Type expectedType, List<Requirement> requirements) {
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
    var result = filteredEvents.get(RequirementKey.from(requirement));
    if (result == null)
      throw new MissingRequirement(format(
          "%s requests result for a requirement which was not specified: %s",
          requirer.getName(),
          requirement
      ));
    return result;
  }

  private Optional<LoadedEvent> filteredForOptionalRequirement(
      Type expectedType,
      List<Requirement> requirements
  ) {
    var result = filteredForRequirement(expectedType, requirements);
    if (result.size() > 1)
      throw new IllegalStateException(
          "More than one requirement matches " + expectedType + " for given event type: " + result.size());
    return Optional.ofNullable(result.size() == 1 ? result.getFirst() : null);
  }

  private LoadedEvent filteredForSingletonRequirement(
      Type expectedType,
      List<Requirement> requirements
  ) {
    List<LoadedEvent> result = filteredForRequirement(expectedType, requirements);
    if (result.size() != 1)
      throw new IllegalStateException(
          "Not exactly one requirement matches " + expectedType + " for given event type: " + result.size());
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
          .filter(event -> requirement.eventTypes().contains(event.getType()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(
              format("Required at least one event of type %s but found 0", formatEventTypes(requirement))));
      case CurrentIfExists -> Optional.of(currentEvent)
          .filter(event -> requirement.eventTypes().contains(event.getType()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(List.of()));
      case Trigger -> Optional.of(triggerEvent)
          .filter(event -> requirement.eventTypes().contains(event.getType()))
          .map(event -> new FilterResult(List.of(event)))
          .orElse(new FilterResult(
              format("Required trigger event to be of type %s but was %s", formatEventTypes(requirement),
                  triggerEvent.getType()
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
        events.stream().filter(e -> types.contains(e.getType()));
  }

}
