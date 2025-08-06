package com.github.thxmasj.statemachine;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.Requirements.Requirement;
import com.github.thxmasj.statemachine.Requirements.Type;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
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

  private final Class<?> requirer;
  private final Requirements requirements;
  private final Map<Requirement, List<Event>> filteredEvents = new HashMap<>();
  private final List<Event> events;
  private final Entity entity;
  private final List<ProcessResult> processResults;
  private final List<Event> processedEvents;
  private final OutgoingRequestByEvent outgoingRequestByEvent;

  public static class RequirementsNotFulfilled extends RuntimeException {

    public RequirementsNotFulfilled(String message) {
      super(message);
    }
  }

  private Mono<HttpRequestMessage> getOutgoingRequest(
      EntityId entityId,
      Event event,
      OutboxQueue queue
  ) {
    return outgoingRequestByEvent.execute(entityId, event.eventNumber());
  }

  public RequiredData(
      Entity entity,
      List<Event> events,
      List<ProcessResult> processResults,
      List<Event> processedEvents,
      Requirements requirements,
      Class<?> requirer,
      OutgoingRequestByEvent outgoingRequestByEvent
  ) {
    this.requirer = requireNonNull(requirer);
    this.requirements = requirements;
    this.events = events;
    this.entity = entity;
    this.processResults = processResults;
    this.processedEvents = processedEvents;
    this.outgoingRequestByEvent = outgoingRequestByEvent;
    for (var requirement : requirements.asList()) {
      FilterResult result = filter(requirement, events);
      if (result.events() != null) {
        filteredEvents.put(requirement, result.events());
      }
    }
  }

  @Override
  public <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return getOutgoingRequest(entity.id(), loadedEvent, queue)
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
