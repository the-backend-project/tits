package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlantUMLFormatterTest {

  enum States implements State {Accepted, Refused}

  static final EventType<Void, Void> Requested = BasicEventType.of("Requested", UUID.randomUUID());
  static final EventType<Void, Void> Accepted = BasicEventType.of("Accepted", UUID.randomUUID());
  static final EventType<Void, Void> Refused = BasicEventType.of("Refused", UUID.randomUUID());
  static final EventType<Void, Void> Withdrawn = BasicEventType.of("Withdrawn", UUID.randomUUID());

  static final EntityModel Request = EntityModel.of("Request", UUID.randomUUID());

  @Test
  void transitionInSeveralBranchesOfAChoiceIsShownOnce() {
    TransitionModel<Void, Void> refuse = onEvent(Refused).to(States.Refused).output();
    Map<State, List<TransitionModel<?, ?>>> transitions = Map.of(
        Begin, List.of(
            onEvent(Requested).to(States.Accepted).assembleInput()
                .when(_ -> false).then(refuse)
                .when(_ -> true).then(refuse)
                .when(_ -> true).then(onEvent(Withdrawn).to(States.Refused).output())
                .otherwise(onEvent(Accepted).to(States.Accepted).output())
        ),
        States.Accepted, List.of(),
        States.Refused, List.of()
    );
    String diagram = new PlantUMLFormatter(Request, transitions).format();
    String refusedId = States.Refused.name() + States.Refused.hashCode();
    assertThat(diagram).contains("Choice1 --> " + refusedId + ": Refused\\nWithdrawn\n");
  }

}
