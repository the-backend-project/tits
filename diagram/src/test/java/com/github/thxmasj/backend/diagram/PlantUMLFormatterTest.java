package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.Severity;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlantUMLFormatterTest {

  enum States implements State {Accepted, Refused, Waiting, Retrying, Failed}

  static final EventType<Void, Void> Requested = BasicEventType.of("Requested", UUID.randomUUID());
  static final EventType<Void, Void> Accepted = BasicEventType.of("Accepted", UUID.randomUUID());
  static final EventType<Void, Void> Refused = BasicEventType.of("Refused", UUID.randomUUID());
  static final EventType<Void, Void> Withdrawn = BasicEventType.of("Withdrawn", UUID.randomUUID());

  /// A formatter of a request model with the transitions
  private static PlantUMLFormatter formatter(Map<State, List<TransitionModel<?, ?>>> transitions) {
    return new PlantUMLFormatter(EntityModel.of("Request", UUID.randomUUID(), () -> transitions));
  }

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
    String diagram = formatter(transitions).format();
    String refusedId = States.Refused.name() + States.Refused.hashCode();
    assertThat(diagram).contains("Choice1 --> " + refusedId + ": Refused\\nWithdrawn\n");
  }

  /// Like the retry of an outbox request after a timeout or a dropped connection, the choices of several transitions
  /// with the same branches are one choice, with the transitions into it on one arrow and its branches drawn once
  @Test
  void choicesWithTheSameBranchesAreOneChoice() {
    EventType<Void, Void> timedOut = BasicEventType.of("Timed out", UUID.randomUUID());
    EventType<Void, Void> dropped = BasicEventType.of("Dropped", UUID.randomUUID());
    EventType<Void, Void> retried = BasicEventType.of("Retried", UUID.randomUUID());
    EventType<Void, Void> givenUp = BasicEventType.of("Given up", UUID.randomUUID());
    Map<State, List<TransitionModel<?, ?>>> transitions = Map.of(
        Begin, List.of(onEvent(Requested).to(States.Waiting).output()),
        States.Waiting, List.of(
            onEvent(timedOut).to(States.Waiting).assembleInput()
                .when(_ -> true).then(onEvent(retried).to(States.Retrying).output())
                .otherwise(onEvent(givenUp).to(States.Failed).output()),
            onEvent(dropped).to(States.Waiting).assembleInput()
                .when(_ -> true).then(onEvent(retried).to(States.Retrying).output())
                .otherwise(onEvent(givenUp).to(States.Failed).output())
        ),
        States.Retrying, List.of(),
        States.Failed, List.of()
    );
    String diagram = formatter(transitions).format();
    String waitingId = States.Waiting.name() + States.Waiting.hashCode();
    String retryingId = States.Retrying.name() + States.Retrying.hashCode();
    assertThat(diagram).containsOnlyOnce("<<choice>>");
    assertThat(diagram).contains(waitingId + " --> Choice1: Timed out\\nDropped\n");
    assertThat(diagram).containsOnlyOnce("Choice1 --> " + retryingId + ": Retried\n");
  }

  /// Choices whose branches differ, here in the state a branch leads to, are separate choices
  @Test
  void choicesWithOtherBranchesAreSeparateChoices() {
    EventType<Void, Void> timedOut = BasicEventType.of("Timed out", UUID.randomUUID());
    EventType<Void, Void> dropped = BasicEventType.of("Dropped", UUID.randomUUID());
    EventType<Void, Void> retried = BasicEventType.of("Retried", UUID.randomUUID());
    Map<State, List<TransitionModel<?, ?>>> transitions = Map.of(
        Begin, List.of(onEvent(Requested).to(States.Waiting).output()),
        States.Waiting, List.of(
            onEvent(timedOut).to(States.Waiting).assembleInput()
                .when(_ -> true).then(onEvent(retried).to(States.Retrying).output())
                .otherwise(onEvent(Refused).to(States.Failed).output()),
            onEvent(dropped).to(States.Waiting).assembleInput()
                .when(_ -> true).then(onEvent(retried).to(States.Retrying).output())
                .otherwise(onEvent(Refused).to(States.Refused).output())
        ),
        States.Retrying, List.of(),
        States.Failed, List.of(),
        States.Refused, List.of()
    );
    String diagram = formatter(transitions).format();
    String waitingId = States.Waiting.name() + States.Waiting.hashCode();
    assertThat(diagram).contains("state Choice1 <<choice>>", "state Choice2 <<choice>>");
    assertThat(diagram).contains(waitingId + " --> Choice1: Timed out\n", waitingId + " --> Choice2: Dropped\n");
  }

  @Test
  void selfTransitionsOfAStateAreStackedOnOneLoop() {
    Map<State, List<TransitionModel<?, ?>>> transitions = Map.of(
        Begin, List.of(onEvent(Requested).to(States.Accepted).output()),
        States.Accepted, List.of(
            onEvent(Refused).toSelf().output(),
            onEvent(Withdrawn).toSelf().output()
        )
    );
    String diagram = formatter(transitions).format();
    String acceptedId = States.Accepted.name() + States.Accepted.hashCode();
    assertThat(diagram).contains(acceptedId + " --> " + acceptedId + ": Refused\\nWithdrawn\n");
  }

  @Test
  void eventNameIsColouredAfterTheSeverityOfItsTransition(@TempDir Path directory) throws IOException {
    Map<State, List<TransitionModel<?, ?>>> transitions = Map.of(
        Begin, List.of(
            onEvent(Requested).to(States.Accepted).output(),
            onEvent(Refused).to(States.Refused).severity(Severity.Warning).output()
        ),
        States.Accepted, List.of(onEvent(Withdrawn).to(States.Refused).severity(Severity.Critical).output()),
        States.Refused, List.of()
    );
    var formatter = formatter(transitions);
    assertThat(formatter.format())
        .contains(": Requested\n")
        .contains(": <color:#7A5200><b>\u26a0 Refused</b></color>\n")
        .contains(": <color:#B00000><b>\u203c Withdrawn</b></color>\n");
    String svg = Files.readString(formatter.formatToImage(new File(directory.toFile(), "Request.svg")).toPath());
    assertThat(svg)
        .containsPattern("<text [^>]*fill=\"#000\"[^>]*>Requested</text>")
        .containsPattern("<text [^>]*fill=\"#7A5200\"[^>]*font-weight=\"700\">\u26a0 Refused</text>")
        .containsPattern("<text [^>]*fill=\"#B00000\"[^>]*font-weight=\"700\">\u203c Withdrawn</text>")
        .contains("g.link text[fill=\"#7A5200\"]{fill:#F2C94C}")
        .doesNotContain("Warning", "Critical");
  }

}
