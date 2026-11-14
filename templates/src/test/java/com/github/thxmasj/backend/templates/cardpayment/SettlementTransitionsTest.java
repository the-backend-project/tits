package com.github.thxmasj.backend.templates.cardpayment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class SettlementTransitionsTest {

  /// A settlement which is left in error is an error for monitoring, and nothing else of a settlement is
  @Test
  public void eventsLeavingASettlementInErrorAreErrors() {
    assertEquals(
        List.of("Imbalance detected: Error", "Settlement timed out: Error"),
        DummyTransitions.settlement().transitions().values().stream()
            .flatMap(List::stream)
            .flatMap(SettlementTransitionsTest::accepting)
            .filter(transition -> transition.severity() != Severity.Info)
            .map(transition -> transition.eventType().name() + ": " + transition.severity())
            .distinct()
            .sorted()
            .toList()
    );
  }

  /// The transitions accepting the events of a transition: itself, or the ones it chooses between
  private static Stream<TransitionModel<?, ?>> accepting(TransitionModel<?, ?> transition) {
    return transition.filters().isEmpty() ?
        Stream.of(transition) :
        transition.filters().stream().flatMap(filter -> accepting(filter.alternative().model()));
  }

}
