package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.JsonLinesListener;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// The events a state machine accepts are written for monitoring, with the severities declared on their transitions
@Tag("database")
public class MonitoringTest {

  enum States implements State { Sent, Lost, Delivered, Returned, Filed, Called, Arrived, Inspecting }

  static EntityModel Parcel = EntityModel.of("Parcel", UUID.fromString("b4d6f8a0-1c3e-4a5b-9d7f-e1a3c5b7d9f1"));
  static EntityModel Complaint = EntityModel.of("Complaint", UUID.fromString("5a7c9e1b-3d5f-4b6c-8e0a-f2b4d6c8e0a2"));
  static EntityModel Inspection = EntityModel.of("Inspection", UUID.fromString("6c8e0a2b-4d6f-4e8a-b0c2-d4e6f8a0b2c5"));
  static EntityModel Courier = EntityModel.of("Courier", UUID.fromString("0b2d4f6a-8c1e-4d3f-a5b7-c9e1a3b5d7f8"));

  static EventType<Void, Void> ParcelSent = BasicEventType.of("Parcel sent", UUID.fromString("c6e8a0b2-4d6f-4c7d-9f1b-a3c5e7d9f1b3"));
  static EventType<Void, Void> DeliveryTimedOut = BasicEventType.of("Delivery timed out", UUID.fromString("7b9d1f3a-5e7a-4d8e-a0c2-b4d6f8e0a2c4"));
  static EventType<Void, Void> ParcelLost = BasicEventType.of("Parcel lost", UUID.fromString("d8f0b2c4-6f8b-4e9f-b1d3-c5e7a9f1b3d5"));
  static EventType<Void, Void> LossConfirmed = BasicEventType.of("Loss confirmed", UUID.fromString("3e5a7c9d-0b2d-4f4a-96c8-e0a2c4b6d8f9"));
  static EventType<UUID, UUID> ComplaintFiled = BasicEventType.of("Complaint filed", UUID.fromString("8c0e2a4b-7a9c-4f0a-82e4-d6f8b0a2c4e6"), DataType.uuid());
  static EventType<String, String> ParcelReceived = BasicEventType.of("Parcel received", UUID.fromString("e0a2c4d6-8b0d-4a1b-93f5-e7a9c1b3d5f7"), DataType.string());
  static EventType<String, String> ParcelReturned = BasicEventType.of("Parcel returned", UUID.fromString("9d1f3b5c-9c1e-4b2c-a4a6-f8b0d2c4e6a8"), DataType.string());
  static EventType<String, String> ParcelDelivered = BasicEventType.of("Parcel delivered", UUID.fromString("f2b4d6e8-0d2f-4c3d-b5b7-a9c1e3d5f7b9"), DataType.string());
  static EventType<Void, Void> ParcelInspected = BasicEventType.of("Parcel inspected", UUID.fromString("2a4c6e8f-0b2d-4f4b-a6c8-e0a2c4e6a8b1"));
  static EventType<UUID, UUID> InspectionStarted = BasicEventType.of("Inspection started", UUID.fromString("b5d7f9a1-3c5e-4a7c-9e1b-d3f5a7c9e1b2"), DataType.uuid());
  static EventType<Void, Void> CourierCalled = BasicEventType.of("Courier called", UUID.fromString("4f6b8d0e-1c3e-4a5c-b7d9-f1a3c5e7b9d0"));
  static EventType<Void, Void> CourierArrived = BasicEventType.of("Courier arrived", UUID.fromString("a1c3e5f7-2d4f-4b6d-88ea-0b2d4f6a8c1e"));

  /// The courier arrives a while after it is called, when the one who called it has stopped listening
  static Action<String> arrival = new Action<>() {
    @Override public String name() {return "Arrival";}
    @Override public Mono<InputEvent<?>> execute(String data) {
      return Mono.delay(Duration.ofMillis(200)).<InputEvent<?>>map(_ -> new InputEvent<>(CourierArrived, null));
    }
  };

  private static final List<String> lines = new CopyOnWriteArrayList<>();
  private static final StateMachine machine = Init.stateMachine(
      UUID.randomUUID().toString(),
      transitions(),
      Clock.systemUTC(),
      new JsonLinesListener(lines::add, Clock.systemUTC())
  );

  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Parcel, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(ParcelSent).to(States.Sent)
                    .trigger(DeliveryTimedOut).after(_ -> Duration.ofDays(1))
                    .output()
            ),
            States.Sent, List.of(
                onEvent(DeliveryTimedOut).toSelf().output(),
                onEvent(ParcelInspected).toSelf()
                    .assemble(c -> c.log().entityId())
                    .trigger(InspectionStarted).with(parcel -> parcel).on(Inspection).identifiedBy(newEntityId())
                    .output(),
                onEvent(ParcelLost).to(States.Lost).severity(Severity.Error)
                    .assemble(c -> c.log().entityId())
                    .trigger(ComplaintFiled).with(parcel -> parcel).on(Complaint).identifiedBy(newEntityId())
                    .output(),
                // Always chooses another transition, so no event is accepted with its severity
                onEvent(ParcelReceived).to(States.Delivered).severity(Severity.Critical)
                    .assembleInput()
                    .when(d -> d.equals("damaged")).then(
                        onEvent(ParcelReturned).to(States.Returned).severity(Severity.Warning).assembleInput().output(d -> d)
                    )
                    .otherwise(onEvent(ParcelDelivered).to(States.Delivered).assembleInput().output(d -> d))
            ),
            States.Lost, List.of(onEvent(LossConfirmed).toSelf().output()),
            States.Delivered, List.of(),
            States.Returned, List.of()
        ),
        Complaint, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                // The parcel is in the middle of its transition, so the loss is confirmed on it after that one
                onEvent(ComplaintFiled).to(States.Filed).severity(Severity.Warning)
                    .assembleInput()
                    .trigger(LossConfirmed).on(Parcel).identifiedBy(parcel -> entityId(parcel))
                    .output(d -> d.t1())
            ),
            States.Filed, List.of()
        ),
        Inspection, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(InspectionStarted).to(States.Inspecting)
                    .<UUID>assemble(_ -> {throw new IllegalStateException("Scanner broken");})
                    .output()
            ),
            States.Inspecting, List.of()
        ),
        Courier, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(CourierCalled).to(States.Called)
                    .assemble(_ -> "")
                    .trigger(arrival).with(d -> d)
                    .output()
            ),
            States.Called, List.of(onEvent(CourierArrived).to(States.Arrived).severity(Severity.Warning).output()),
            States.Arrived, List.of()
        )
    );
  }

  @Test
  public void anEventIsWrittenWithItsEntityAndTime() {
    String correlationId = UUID.randomUUID().toString();
    Event<?> sent = machine.onEvent(correlationId, trigger(ParcelSent, Parcel)).blockFirst();

    // The delayed event is not written, as it is yet to happen
    assertEquals(1, linesOf(correlationId).size());
    Map<String, Object> line = linesOf(correlationId).getFirst();
    assertEquals("INFO", line.get("severity"));
    assertEquals("Parcel sent on Parcel " + sent.entityId(), line.get("message"));
    assertEquals("Parcel", line.get("entityModel"));
    assertEquals(sent.entityId().toString(), line.get("entityId"));
    assertEquals(1, line.get("eventNumber"));
    assertEquals("Parcel sent", line.get("eventType"));
    assertEquals(sent.timestamp().toInstant(), Instant.parse((String) line.get("time")));
  }

  @Test
  public void theEventsOfAChangeAreWrittenInTheOrderTheyWereCalculatedWithTheSeveritiesOfTheirTransitions() {
    UUID parcel = machine.onEvent(trigger(ParcelSent, Parcel)).blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    machine.onEvent(correlationId, trigger(ParcelLost, Parcel, parcel)).blockFirst();

    assertEquals(
        List.of(
            "WARNING Complaint filed on Complaint, event 1",
            "ERROR Parcel lost on Parcel, event 2",
            "INFO Loss confirmed on Parcel, event 3"
        ),
        summariesOf(correlationId)
    );
  }

  @Test
  public void theEventOfAChosenTransitionIsWrittenWithTheSeverityOfThatTransition() {
    assertEquals(List.of("WARNING Parcel returned on Parcel, event 2"), received("damaged"));
    assertEquals(List.of("INFO Parcel delivered on Parcel, event 2"), received("intact"));
  }

  /// The event of the arrival is stored after the one who called the courier has taken the event of the call, and
  /// listens for no more
  @Test
  public void anEventIsWrittenAlsoWhenTheOneWhoTriggeredItHasStoppedListening() throws InterruptedException {
    String correlationId = UUID.randomUUID().toString();
    machine.onEvent(correlationId, trigger(CourierCalled, Courier)).blockFirst();

    for (int i = 0; i < 100 && linesOf(correlationId).size() < 2; i++)
      Thread.sleep(100);
    assertEquals(
        List.of("INFO Courier called on Courier, event 1", "WARNING Courier arrived on Courier, event 2"),
        summariesOf(correlationId)
    );
  }

  /// The inspection, which the parcel triggers, fails, and the failure is written once, for the inspection, while the
  /// one who triggered the inspection of the parcel gets the exception
  @Test
  public void aFailedTransitionIsWrittenAsAnErrorOfTheTransitionItFailedIn() {
    UUID parcel = machine.onEvent(trigger(ParcelSent, Parcel)).blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    var failure = assertThrows(
        IllegalStateException.class,
        () -> machine.onEvent(correlationId, trigger(ParcelInspected, Parcel, parcel)).blockFirst()
    );
    assertEquals("Scanner broken", failure.getMessage());

    assertEquals(1, linesOf(correlationId).size());
    Map<String, Object> line = linesOf(correlationId).getFirst();
    assertEquals("ERROR", line.get("severity"));
    assertEquals("Inspection", line.get("entityModel"));
    assertEquals("Inspection started", line.get("eventType"));
    assertEquals(null, line.get("eventNumber"));
    assertEquals(
        "Inspection started on Inspection " + line.get("entityId") + " failed: java.lang.IllegalStateException: Scanner broken",
        line.get("message")
    );
  }

  /// What is written when a parcel is received in the given condition
  private static List<String> received(String condition) {
    UUID parcel = machine.onEvent(trigger(ParcelSent, Parcel)).blockFirst().entityId();
    String correlationId = UUID.randomUUID().toString();
    machine.onEvent(correlationId, trigger(ParcelReceived, Parcel, parcel), condition).blockFirst();
    return summariesOf(correlationId);
  }

  private static List<String> summariesOf(String correlationId) {
    return linesOf(correlationId).stream()
        .map(line -> line.get("severity") + " " + line.get("eventType") + " on " + line.get("entityModel") + ", event " + line.get("eventNumber"))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> linesOf(String correlationId) {
    return lines.stream()
        .map(line -> (Map<String, Object>) DataType.objectMapper.readValue(line, Map.class))
        .filter(line -> correlationId.equals(line.get("correlationId")))
        .toList();
  }

}
