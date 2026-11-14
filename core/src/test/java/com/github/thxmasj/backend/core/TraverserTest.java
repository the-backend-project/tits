package com.github.thxmasj.backend.core;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class TraverserTest {

  enum States implements State {Requested, Accepted, Rejected}

  static EventType<Void, Void>
      Requested = BasicEventType.of("Requested", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a01")),
      Retried = BasicEventType.of("Retried", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a02")),
      Accepted = BasicEventType.of("Accepted", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a03")),
      Rejected = BasicEventType.of("Rejected", UUID.fromString("2c5f0a4e-8d1b-4f6a-9c3e-7b2d1e0f4a04"));

  /// Shared by the choices, as the same transition reached by more than one path
  private static final TransitionModel<Void, Void> acceptance = onEvent(Accepted).to(States.Accepted).assembleInput().output();

  private static TransitionModel<Void, Void> reject(State to) {
    return onEvent(Rejected).to(to).assembleInput().output();
  }

  private static TransitionModel<Void, Void> choosing(EventType<Void, Void> type, TransitionModel<Void, Void> rejection) {
    return onEvent(type).to(States.Requested)
        .assembleInput()
        .when(_ -> false).then(rejection)
        .otherwise(acceptance);
  }

  @Test
  public void uniquePathsAreAccepted() {
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected))),
        States.Accepted, List.of(reject(States.Rejected))
    )));
  }

  @Test
  public void sameTransitionByTwoPathsIsAccepted() {
    var rejection = reject(States.Rejected);
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, rejection), choosing(Retried, rejection))
    )));
  }

  @Test
  public void alikeTransitionsBuiltSeparatelyAreAccepted() {
    assertDoesNotThrow(() -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reject(States.Rejected)))
    )));
  }

  @Test
  public void twoChoicesOfTheSameEventTypeAreRejected() {
    var e = assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reject(States.Requested)))
    )));
    assertTrue(e.getMessage().startsWith("Event type Rejected has transitions from Begin "), e.getMessage());
  }

  @Test
  public void alikeTransitionsWithDifferentReverseModelsAreRejected() {
    var reversibleRejection = onEvent(Rejected).to(States.Rejected)
        .assembleInput()
        .reversible(assemble((_, _) -> null))
        .output();
    assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), choosing(Retried, reversibleRejection))
    )));
  }

  @Test
  public void choiceOfAnEventTypeTransitionedOnDirectlyIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Traverser(Map.of(
        Begin, List.of(choosing(Requested, reject(States.Rejected)), reject(States.Requested))
    )));
  }

}
