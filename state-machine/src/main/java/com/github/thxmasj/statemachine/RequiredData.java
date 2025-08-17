package com.github.thxmasj.statemachine;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.Requirements.MissingRequirement;
import com.github.thxmasj.statemachine.Requirements.Requirement;
import com.github.thxmasj.statemachine.Requirements.Requirement.Type;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult.Entity;
import com.github.thxmasj.statemachine.database.mssql.OutgoingRequestByEvent;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.time.ZonedDateTime;
import java.util.List;
import reactor.core.publisher.Mono;

public class RequiredData implements Input {

  private final Class<?> requirer;
  private final Requirements requirements;
  private final List<Event<?>> events;
  private final EntityId entityId;
  private final List<ProcessResult> processResults;
  private final List<Event<?>> processedEvents;
  private final OutgoingRequestByEvent outgoingRequestByEvent;

  public static class RequirementsNotFulfilled extends RuntimeException {

    public RequirementsNotFulfilled(String message) {
      super(message);
    }
  }

  private Mono<HttpRequestMessage> getOutgoingRequest(
      EntityId entityId,
      Event<?> event,
      OutboxQueue queue
  ) {
    return outgoingRequestByEvent.execute(entityId, event.eventNumber());
  }

  public RequiredData(
      EntityId entityId,
      List<Event<?>> events,
      List<ProcessResult> processResults,
      List<Event<?>> processedEvents,
      Requirements requirements,
      Class<?> requirer,
      OutgoingRequestByEvent outgoingRequestByEvent
  ) {
    this.requirer = requireNonNull(requirer);
    this.requirements = requirements;
    this.events = events;
    this.entityId = entityId;
    this.processResults = processResults;
    this.processedEvents = processedEvents;
    this.outgoingRequestByEvent = outgoingRequestByEvent;
  }

  @Override
  public <T> Mono<OutgoingRequest<T>> outgoingRequest(OutboxQueue queue, EventType<?, ?> eventType, Class<T> type) {
    var loadedEvent = last(eventType);
    return getOutgoingRequest(entityId, loadedEvent, queue)
        .map(message -> new OutgoingRequest<>(
                message,
                loadedEvent.eventNumber()
            )
        );
  }

  private <T> Event<T> last(EventType<?, T> eventType) {
    var matchingRequirements = requirements.on(eventType);
    if (matchingRequirements.isEmpty()) {
      throw new MissingRequirement(requirer.getName() + ": last(" + eventType + "): No requirements found for " + eventType);
    }
    return filteredForSingletonRequirement(Requirement.Type.Last, matchingRequirements);
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
  public <T> Event<T> processedEvent(EventType<?, T> eventType, Class<T> dataType) {
    return processedEvents.stream()
        .filter(e -> e.type().outputDataType() == dataType)
        .map(e -> (Event<T>) e)
        .filter(e -> e.type() == eventType)
        .findFirst()
        .orElseThrow();
  }

  @Override
  public ZonedDateTime timestamp() {
    return events.isEmpty() ? null : events.getFirst().timestamp();
  }

  private <T> List<Event<T>> filteredForRequirement(Type expectedType, List<Requirement<T>> requirements) {
    Requirement<T> requirement = requirements
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
    return events.stream()
        .map(e -> (Event<T>) e)
        .filter(e -> requirement.eventTypes().contains(e.type()))
        .toList();
  }

  private <T> Event<T> filteredForSingletonRequirement(
      Type expectedType,
      List<Requirement<T>> requirements
  ) {
    List<Event<T>> result = filteredForRequirement(expectedType, requirements);
    if (result.size() != 1)
      throw new IllegalStateException(String.format(
          """
          Not exactly one event matches singleton requirement type %s.
          Filtered events:
          %s
          All events:
          %s
          Requirements:
          %s
          """,
          expectedType,
          result.stream().map(Event::toString).collect(joining("\n")),
          events.stream().map(Event::toString).collect(joining("\n")),
          requirements.stream().map(Object::toString).collect(joining("\n"))
      ));
    return result.getFirst();
  }

}
