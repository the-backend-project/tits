package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.internal.Calculation;
import com.github.thxmasj.backend.core.internal.TransitionStep.InputStep;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// The severity of an event is the one declared on the transition accepting it
public class SeverityTest {

  enum States implements State { Delivered, Returned }

  static EntityModel Parcel = EntityModel.of("Parcel", UUID.fromString("2b6d0c4e-7a1f-4e39-9c58-d3f1a5b7c9e0"));

  static EventType<String, String> ParcelReceived = BasicEventType.of("Parcel received", UUID.fromString("8c1e3a5d-2f4b-4d6a-b8c0-e2f4a6c8d0b1"), DataType.string());
  static EventType<String, String> ParcelDelivered = BasicEventType.of("Parcel delivered", UUID.fromString("4e6a8c0f-1b3d-4f5b-a7c9-d1e3f5a7b9c2"), DataType.string());
  static EventType<String, String> ParcelReturned = BasicEventType.of("Parcel returned", UUID.fromString("a0c2e4f6-3d5b-4a7c-9e1f-b3d5f7a9c1e3"), DataType.string());
  static EventType<String, String> ReceiptReminded = BasicEventType.of("Receipt reminded", UUID.fromString("6f8b0d2e-5c7a-4b9d-8f1a-c3e5a7b9d1f4"), DataType.string());
  static EventType<String, String> ParcelWeighed = BasicEventType.of("Parcel weighed", UUID.fromString("c2e4a6b8-7d9f-4c1e-a3b5-d7f9b1c3e5a6"), DataType.string());
  static EventType<UUID, UUID> ParcelReferenced = IndexEntityModel.referenceEventType("Parcel referenced", UUID.fromString("1d3f5b7c-8e0a-4d2f-b4c6-e8a0c2d4f6b7"));

  @Test
  public void aTransitionIsOfSeverityInfoUnlessItDeclaresAnother() {
    assertEquals(Severity.Info, onEvent(ParcelDelivered).to(States.Delivered).output().severity());
    assertEquals(
        Severity.Warning,
        onEvent(ParcelDelivered).to(States.Delivered).severity(Severity.Warning).output().severity()
    );
  }

  @Test
  public void theStepsOfATransitionKeepItsSeverity() {
    Action<String> notify = new Action<>() {
      @Override public String name() {return "Notify";}
      @Override public Mono<InputEvent<?>> execute(String data) {return Mono.empty();}
    };
    TransitionModel<String, String> transition = onEvent(ParcelDelivered).to(States.Delivered).severity(Severity.Critical)
        .assembleInput()
        .trigger(ReceiptReminded).with(d -> d).after(_ -> Duration.ofHours(1))
        .trigger(notify).with(d -> d)
        .when(d -> d.isEmpty()).then(onEvent(ParcelReturned).to(States.Returned).assembleInput().output(d -> d))
        .reversible(TransitionModelBuilder.assemble((_, _) -> ""))
        .trigger(ParcelWeighed).with(d -> d).on(Parcel).identifiedBy(entityIdFromSession())
        .trigger(ParcelReturned).when(ParcelReferenced, _ -> UUID.randomUUID()).is(ParcelWeighed)
            .with(d -> d.t1()).on(Parcel).identifiedBy(entityIdFromSession())
        .output(d -> d.t1());

    assertEquals(Severity.Critical, transition.severity());
  }

  @Test
  public void anEventIsAcceptedWithTheSeverityOfItsTransition() {
    TransitionModel<String, String> transition = onEvent(ParcelDelivered).to(States.Delivered).severity(Severity.Error)
        .assembleInput()
        .output(d -> d);

    assertEquals(Severity.Error, transition.calculate(input(transition, "1")).block().stepOutput().accepted().severity());
  }

  @Test
  public void theEventOfAChosenTransitionIsAcceptedWithTheSeverityOfThatTransition() {
    TransitionModel<String, String> transition = onEvent(ParcelReceived).to(States.Delivered).severity(Severity.Critical)
        .assembleInput()
        .when(d -> d.equals("damaged")).then(
            onEvent(ParcelReturned).to(States.Returned).severity(Severity.Warning).assembleInput().output(d -> d)
        )
        .otherwise(onEvent(ParcelDelivered).to(States.Delivered).assembleInput().output(d -> d));

    assertEquals(Severity.Warning, chosen(transition, "damaged"));
    assertEquals(Severity.Info, chosen(transition, "intact"));
  }

  /// The severity which the event of the transition chosen for the input is accepted with
  private static Severity chosen(TransitionModel<String, String> transition, String input) {
    return transition.calculate(input(transition, input)).block().stepOutput().completed().choiceResult().accepted().severity();
  }

  private static InputStep<String> input(TransitionModel<String, String> transition, String input) {
    return new InputStep<>(
        null,
        null,
        null,
        transition,
        Begin,
        1,
        EventLog.empty(UUID.randomUUID(), Parcel),
        ZonedDateTime.now(),
        "correlation",
        input,
        null,
        new Calculation()
    );
  }

}
