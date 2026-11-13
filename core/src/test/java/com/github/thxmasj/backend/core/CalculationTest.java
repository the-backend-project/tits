package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.TransitionStep.AssembleStep;
import com.github.thxmasj.backend.core.TransitionStep.InputStep;
import com.github.thxmasj.backend.core.TransitionStep.OutputStep;
import com.github.thxmasj.backend.core.TransitionStep.RejectedStep;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class CalculationTest {

  static EventType<String, String> NumberClaimed = BasicEventType.of("Number claimed", UUID.fromString("0d2f4a6c-8e9b-4b1d-a3f5-7c9e1b3d5f17"), DataType.string());
  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("f5a7c9e1-3b4d-4d6f-8a0c-2e4f6a8c0e29"), NumberClaimed);
  static EntityModel Order = EntityModel.of("Order", UUID.fromString("a1c3e5f7-9b0d-4f2a-8c4e-6a8c0e2b4d3b"));

  enum States implements State { Placed, Rejected }

  static EventType<String, String> OrderPlaced = BasicEventType.of("Order placed", UUID.fromString("5c7e9a1b-3d4f-4b6c-8e0a-2c4e6a8c0e4d"), DataType.string());
  static EventType<String, String> OrderAccepted = BasicEventType.of("Order accepted", UUID.fromString("b9d1f3a5-7c8e-4d0f-a2b4-6d8f0a2c4e5f"), DataType.string());
  static EventType<String, String> OrderRejected = BasicEventType.of("Order rejected", UUID.fromString("3e5a7c9d-1f2b-4a4c-8e6f-0b2d4f6a8c61"), DataType.string());
  static EventType<String, String> ReminderSent = BasicEventType.of("Reminder sent", UUID.fromString("d7f9b1c3-5e6a-4c8d-9a0b-2e4a6c8e0a73"), DataType.string());
  static EventType<String, String> OrderShipped = BasicEventType.of("Order shipped", UUID.fromString("7a9c1e3f-5b6d-4e8a-b0c2-4a6c8e0b2d85"), DataType.string());
  static EventType<UUID, UUID> OrderReferenced = IndexEntityModel.referenceEventType("Order referenced", UUID.fromString("1c3e5a7b-9d0f-4a2c-8e4b-6c8e0a2d4f97"));

  private final Calculation calculation = new Calculation();
  private final InputStep<String> order = initial(null, Order);
  // The steps executed, in order
  private final List<String> executed = new ArrayList<>();

  @Test
  public void everyStepOfATransitionIsAStepOfTheCalculation() {
    Action<String> notify = new Action<>() {
      @Override public String name() {return "Notify";}
      @Override public Mono<InputEvent<?>> execute(String data) {return Mono.empty();}
    };
    TransitionModel<String, String> transition = onEvent(OrderPlaced).to(States.Placed)
        .assembleReactive(c -> Mono.fromSupplier(() -> executed("Assemble", c.input())))
        .trigger(ReminderSent).with(d -> executed("Delayed data", d)).after(d -> executed("Delay", Duration.ofHours(1)))
        .trigger(notify).with(d -> executed("Action data", d))
        .trigger(OrderShipped).when(OrderReferenced, d -> executed("Index key", UUID.randomUUID())).is(OrderAccepted)
            .with(d -> executed("Conditional data", d)).on(Order).identifiedBy(entityIdFromSession())
        .when(d -> executed("Reject?", false)).then(onEvent(OrderRejected).to(States.Rejected).assembleInput().output(d -> d))
        .when(d -> executed("Accept?", true)).then(
            onEvent(OrderAccepted).to(States.Placed)
                .assemble(c -> executed("Assemble accepted", c.input()))
                .output(d -> executed("Output accepted", d)),
            d -> executed("Accepted data", d)
        )
        .output(d -> executed("Output", d));
    var output = transition.calculate(order).block();
    assertEquals(
        List.of(
            "Assemble", "Delayed data", "Delay", "Action data", "Conditional data", "Index key", "Reject?",
            "Accept?", "Accepted data", "Assemble accepted", "Output accepted", "Output"
        ),
        executed
    );
    // An id triggered after the transition is found to exist
    var id = step("Trigger", output, () -> initial(output, Ids));
    step("Output id", id, () -> output(id, "1"));
    assertTrue(calculation.recalculateWith(existing("1")));
    executed.clear();

    assertSame(output, transition.calculate(order).block());
    assertEquals(List.of(), executed);
  }

  @Test
  public void stepsBeforeTheTriggerOfAnExistingIdAreNotExecutedAgain() {
    var assembled = step("Assemble", order, () -> new AssembleStep<>(order, "1"));
    var id = step("Trigger", assembled, () -> initial(assembled, Ids));
    var idOutput = step("Output id", id, () -> output(id, "1"));
    step("Output", idOutput, () -> new AssembleStep<>(idOutput, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    assertEquals(1, calculation.existingIds().size());
    assertSame(assembled, step("Assemble", order, () -> new AssembleStep<>(order, "1")));
    var idExists = step("Trigger", assembled, () -> new AssembleStep<>(assembled, "Exists"));
    step("Output", idExists, () -> new AssembleStep<>(idExists, "1"));
    assertEquals(List.of("Trigger", "Output"), executed);
  }

  @Test
  public void changesAreCalculatedAgainFromTheFirstTriggerOfTheId() {
    var first = step("Trigger", order, () -> initial(order, Ids));
    var firstOutput = step("Output id", first, () -> output(first, "1"));
    var second = step("Trigger again", firstOutput, () -> initial(firstOutput, Ids));
    step("Output id again", second, () -> output(second, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    step("Trigger", order, () -> new AssembleStep<>(order, "Exists"));
    assertEquals(List.of("Trigger"), executed);
  }

  @Test
  public void failedStepFailsAgainWithoutBeingExecuted() {
    var failure = new IllegalStateException("Failed");
    assertSame(failure, assertThrows(IllegalStateException.class, () -> step("Fail", order, () -> {
      throw failure;
    })));
    var handled = step("Handle", order, () -> new AssembleStep<>(order, "Handled"));
    var id = step("Trigger", handled, () -> initial(handled, Ids));
    step("Output id", id, () -> output(id, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    assertSame(failure, assertThrows(IllegalStateException.class, () -> step("Fail", order, () -> "Not failed")));
    assertSame(handled, step("Handle", order, () -> new AssembleStep<>(order, "Handled")));
    assertEquals(List.of(), executed);
  }

  @Test
  public void allStepsAreExecutedAgainWhenAResultBeforeTheTriggerIsRejected() {
    var rejected = step("Reject", order, () -> new RejectedStep<>(
        order,
        ProcessResult.rejected(NumberClaimed, new RejectedEvent(NumberClaimed, order.log(), Begin))
    ));
    var id = step("Trigger", rejected, () -> initial(rejected, Ids));
    step("Output id", id, () -> output(id, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    step("Reject", order, () -> new AssembleStep<>(order, "Rejected again"));
    assertEquals(List.of("Reject"), executed);
  }

  @Test
  public void changesAreNotCalculatedAgainWhenTheIdIsNotOfATrigger() {
    // The id event is the event itself, calculated from the first transition step
    InputStep<String> id = initial(null, Ids);
    var assembled = step("Assemble", id, () -> new AssembleStep<>(id, "1"));
    step("Output id", assembled, () -> output(id, "1"));

    assertFalse(calculation.recalculateWith(existing("1")));
    assertFalse(calculation.recalculateWith(existing("2")));

    assertEquals(List.of(), calculation.existingIds());
  }

  @Test
  public void changesAreNotCalculatedAgainWhenTheIdIsFoundToExistOnceMore() {
    var id = step("Trigger", order, () -> initial(order, Ids));
    step("Output id", id, () -> output(id, "1"));
    assertTrue(calculation.recalculateWith(existing("1")));
    // The trigger does not result in the id existing, as it would if the id was that of the data it is triggered with
    var again = step("Trigger", order, () -> initial(order, Ids));
    step("Output id", again, () -> output(again, "1"));

    assertFalse(calculation.recalculateWith(existing("1")));
  }

  @Test
  public void recordedStepsAreNotValidAfterAnotherStepIsTaken() {
    var assembled = step("Assemble", order, () -> new AssembleStep<>(order, "1"));
    var reserved = step("Reserve", assembled, () -> new AssembleStep<>(assembled, "Reserved"));
    var id = step("Trigger", reserved, () -> initial(reserved, Ids));
    step("Output id", id, () -> output(id, "1"));
    assertTrue(calculation.recalculateWith(existing("1")));
    executed.clear();

    // Not the context the first step was executed in
    var other = initial(null, Order);
    step("Assemble other", other, () -> new AssembleStep<>(other, "1"));
    step("Reserve", assembled, () -> new AssembleStep<>(assembled, "Reserved"));

    assertEquals(List.of("Assemble other", "Reserve"), executed);
  }

  private <T> T executed(String step, T result) {
    executed.add(step);
    return result;
  }

  /// Takes a step of the calculation, which gets the result of the execution if the step is executed
  private <T> T step(String name, TransitionStep<?> context, Supplier<T> execution) {
    return calculation.step(context, () -> {
      executed.add(name);
      return Mono.just(execution.get());
    }).block();
  }

  private InputStep<String> initial(TransitionStep<?> previous, EntityModel entityModel) {
    return new InputStep<>(
        null,
        previous,
        null,
        null,
        Begin,
        1,
        EventLog.empty(UUID.randomUUID(), entityModel),
        ZonedDateTime.now(),
        "correlation",
        "input",
        null,
        calculation
    );
  }

  /// The output of the transition of an id event, calculated from the given initial context
  private static OutputStep<String> output(InputStep<String> initial, String id) {
    return new OutputStep<>(
        new AssembleStep<>(initial, id),
        ProcessResult.accepted(idEvent(id), Ids)
    );
  }

  private static IdExists<String> existing(String id) {
    return ProcessResult.idExists(NumberClaimed, idEvent(id), EventLog.empty(UUID.randomUUID(), Order));
  }

  private static Event<String> idEvent(String id) {
    return new Event<>(UUID.randomUUID(), 1, NumberClaimed, ZonedDateTime.now(), id);
  }

}
