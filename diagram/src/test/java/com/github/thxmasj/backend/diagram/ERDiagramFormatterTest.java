package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
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

class ERDiagramFormatterTest {

  enum States implements State {Moving, Stopped}

  static final EventType<Void, Void> CarStarted = BasicEventType.of("Car started", UUID.randomUUID());
  static final EventType<Void, Void> CarStopped = BasicEventType.of("Car stopped", UUID.randomUUID());
  static final EventType<Void, Void> SpeedIncreased = BasicEventType.of("Speed increased", UUID.randomUUID());
  static final EventType<Void, Void> SpeedDecreased = BasicEventType.of("Speed decreased", UUID.randomUUID());
  static final EventType<String, String> NameClaimed = BasicEventType.of("Name claimed", UUID.randomUUID(), DataType.string());

  static final EntityModel Car = EntityModel.of("Car", UUID.randomUUID());
  static final EntityModel Speed = EntityModel.of("Speed", UUID.randomUUID());
  static final EntityModel Engine = EntityModel.of("Engine", UUID.randomUUID());
  static final IdEntityModel Ids = IdEntityModel.of("Ids", UUID.randomUUID(), NameClaimed);

  static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> model() {
    return Map.of(
        Car, Map.of(
            Begin, List.of(
                onEvent(CarStarted).to(States.Moving)
                    .trigger(SpeedIncreased).on(Speed).identifiedBy(newEntityId())
                    .reversible(
                        assemble((_, _) -> UUID.randomUUID())
                            .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                    )
                    .output()
            ),
            States.Moving, List.of(
                onEvent(CarStopped).to(States.Stopped)
                    .trigger(SpeedDecreased).on(Speed).identifiedBy(newEntityId())
                    .trigger(CarStopped).on(Engine).identifiedBy(newEntityId())
                    .output()
            ),
            States.Stopped, List.of()
        ),
        Speed, Map.of(
            Begin, List.of(
                onEvent(SpeedIncreased).toSelf().output(),
                onEvent(SpeedDecreased).toSelf().output()
            )
        ),
        Ids, Ids.transitions()
    );
  }

  @Test
  void entitiesWithTheirEventTypes() {
    String diagram = new ERDiagramFormatter("Test", model()).format();
    assertThat(diagram).contains(
        """
        entity "Car" as E1 {
          {field} Car started
          {field} Car stopped
        }
        """,
        """
        entity "Ids" as E3 <<id>> {
          {field} Name claimed : string → string
        }
        """,
        """
        entity "Speed" as E4 {
          {field} Speed increased
          {field} Speed decreased
        }
        """
    );
  }

  @Test
  void entitiesOutsideTheModelAreIncludedWithTheEventTypesTriggeredOnThem() {
    assertThat(new ERDiagramFormatter("Test", model()).format()).contains(
        """
        entity "Engine" as E2 {
          {field} Car stopped
        }
        """
    );
  }

  @Test
  void triggersRelateEntities() {
    String diagram = new ERDiagramFormatter("Test", model()).format();
    assertThat(diagram).contains(
        "E1 --> E4 : Car started ↪ Speed increased\\n<color:#7a0000>Car started ↪ Speed decreased</color>\\nCar stopped ↪ Speed decreased\n",
        "E1 --> E2 : Car stopped ↪ Car stopped\n"
    );
    assertThat(diagram).doesNotContain("E3 -->", "E4 -->");
  }

  @Test
  void compactDiagramOmitsTheEventTypesOfTheEntities() {
    String diagram = new ERDiagramFormatter("Test", model()).compact().format();
    assertThat(diagram).contains(
        "entity \"Car\" as E1 {\n}\n",
        "entity \"Engine\" as E2 {\n}\n",
        "entity \"Ids\" as E3 <<id>> {\n}\n",
        "entity \"Speed\" as E4 {\n}\n",
        "E1 --> E2 : Car stopped ↪ Car stopped\n"
    );
    assertThat(diagram).doesNotContain("{field}");
  }

  @Test
  void compactDiagramRendersAsAnERDiagram(@TempDir Path directory) throws IOException {
    File file = new ERDiagramFormatter("Test", model()).compact().formatToImage(directory.toString());
    // The relations are links between entities, not messages between the participants of a sequence diagram
    assertThat(Files.readString(file.toPath())).contains("class=\"link\"").doesNotContain("class=\"message\"");
  }

  @Test
  void rendersToSvg(@TempDir Path directory) throws IOException {
    File file = new ERDiagramFormatter("Test", model()).formatToImage(directory.toString());
    assertThat(file.getName()).isEqualTo("Test.er.svg");
    assertThat(Files.readString(file.toPath())).startsWith("<svg").contains("Car", "Speed", "Engine", "Ids");
  }

}
