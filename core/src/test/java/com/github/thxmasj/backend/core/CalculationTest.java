package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.StateMachine.ProcessResult;
import com.github.thxmasj.backend.core.StateMachine.ProcessResult.IdExists;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.AssembledChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.InitialChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.OutputChangeContext;
import com.github.thxmasj.backend.core.TransitionModelBuilder.ChangeContext.RejectedChangeContext;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class CalculationTest {

  static EventType<String, String> Number = BasicEventType.of("Number", UUID.fromString("0d2f4a6c-8e9b-4b1d-a3f5-7c9e1b3d5f17"), DataType.string());
  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("f5a7c9e1-3b4d-4d6f-8a0c-2e4f6a8c0e29"), Number);
  static EntityModel Order = EntityModel.of("Order", UUID.fromString("a1c3e5f7-9b0d-4f2a-8c4e-6a8c0e2b4d3b"));

  private final Calculation calculation = new Calculation();
  private final InitialChangeContext<String> order = initial(null, Order);
  // The steps executed, in order
  private final List<String> executed = new ArrayList<>();

  @Test
  public void stepsBeforeTheTriggerOfAnExistingIdAreNotExecutedAgain() {
    var assembled = step("Assemble", order, () -> new AssembledChangeContext<>(order, "1"));
    var id = step("Trigger", assembled, () -> initial(assembled, Ids));
    var idOutput = step("Output id", id, () -> output(id, "1"));
    step("Output", idOutput, () -> new AssembledChangeContext<>(idOutput, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    assertEquals(1, calculation.existingIds().size());
    assertSame(assembled, step("Assemble", order, () -> new AssembledChangeContext<>(order, "1")));
    var idExists = step("Trigger", assembled, () -> new AssembledChangeContext<>(assembled, "Exists"));
    step("Output", idExists, () -> new AssembledChangeContext<>(idExists, "1"));
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

    step("Trigger", order, () -> new AssembledChangeContext<>(order, "Exists"));
    assertEquals(List.of("Trigger"), executed);
  }

  @Test
  public void failedStepFailsAgainWithoutBeingExecuted() {
    var failure = new IllegalStateException("Failed");
    assertSame(failure, assertThrows(IllegalStateException.class, () -> step("Fail", order, () -> {
      throw failure;
    })));
    var handled = step("Handle", order, () -> new AssembledChangeContext<>(order, "Handled"));
    var id = step("Trigger", handled, () -> initial(handled, Ids));
    step("Output id", id, () -> output(id, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    assertSame(failure, assertThrows(IllegalStateException.class, () -> step("Fail", order, () -> "Not failed")));
    assertSame(handled, step("Handle", order, () -> new AssembledChangeContext<>(order, "Handled")));
    assertEquals(List.of(), executed);
  }

  @Test
  public void allStepsAreExecutedAgainWhenAResultBeforeTheTriggerIsRejected() {
    var rejected = step("Reject", order, () -> new RejectedChangeContext<>(
        order,
        ProcessResult.rejected(Number, new RejectedEvent(Number, order.log(), Begin))
    ));
    var id = step("Trigger", rejected, () -> initial(rejected, Ids));
    step("Output id", id, () -> output(id, "1"));
    executed.clear();

    assertTrue(calculation.recalculateWith(existing("1")));

    step("Reject", order, () -> new AssembledChangeContext<>(order, "Rejected again"));
    assertEquals(List.of("Reject"), executed);
  }

  @Test
  public void changesAreNotCalculatedAgainWhenTheIdIsNotOfATrigger() {
    // The id event is the event itself, calculated from the first change context
    InitialChangeContext<String> id = initial(null, Ids);
    var assembled = step("Assemble", id, () -> new AssembledChangeContext<>(id, "1"));
    step("Output id", assembled, () -> output(id, "1"));

    assertFalse(calculation.recalculateWith(existing("1")));
    assertFalse(calculation.recalculateWith(existing("2")));

    assertEquals(List.of(), calculation.existingIds());
  }

  @Test
  public void recordedStepsAreNotValidAfterAnotherStepIsTaken() {
    var assembled = step("Assemble", order, () -> new AssembledChangeContext<>(order, "1"));
    var reserved = step("Reserve", assembled, () -> new AssembledChangeContext<>(assembled, "Reserved"));
    var id = step("Trigger", reserved, () -> initial(reserved, Ids));
    step("Output id", id, () -> output(id, "1"));
    assertTrue(calculation.recalculateWith(existing("1")));
    executed.clear();

    // Not the context the first step was executed in
    var other = initial(null, Order);
    step("Assemble other", other, () -> new AssembledChangeContext<>(other, "1"));
    step("Reserve", assembled, () -> new AssembledChangeContext<>(assembled, "Reserved"));

    assertEquals(List.of("Assemble other", "Reserve"), executed);
  }

  /// Takes a step of the calculation, which gets the result of the execution if the step is executed
  private <T> T step(String name, ChangeContext<?> context, Supplier<T> execution) {
    return calculation.step(context, () -> {
      executed.add(name);
      return Mono.just(execution.get());
    }).block();
  }

  private InitialChangeContext<String> initial(ChangeContext<?> previous, EntityModel entityModel) {
    return new InitialChangeContext<>(
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
  private static OutputChangeContext<String> output(InitialChangeContext<String> initial, String id) {
    return new OutputChangeContext<>(
        new AssembledChangeContext<>(initial, id),
        ProcessResult.accepted(idEvent(id), Ids)
    );
  }

  private static IdExists<String> existing(String id) {
    return ProcessResult.idExists(Number, idEvent(id), EventLog.empty(UUID.randomUUID(), Order));
  }

  private static Event<String> idEvent(String id) {
    return new Event<>(UUID.randomUUID(), 1, Number, ZonedDateTime.now(), id);
  }

}
