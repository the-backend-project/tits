package com.github.thxmasj.backend.diagram;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventTrigger;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

/// An ER diagram of a state machine's complete model, i.e. the transitions of all its entity models, as given to the
/// state machine. Each entity model is an entity listing the event types it accepts, and the entity models are related
/// through the triggers of their transitions: an arrow from one entity to another tells that an event on the first
/// triggers an event on the other. Triggers of reversals are red.
public class ERDiagramFormatter {

  private final String name;
  private final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions;
  private final boolean compact;

  /// @param name the name of the model, used for the names of the files the diagram is written to
  public ERDiagramFormatter(String name, Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) {
    this(name, transitions, false);
  }

  private ERDiagramFormatter(
      String name,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      boolean compact
  ) {
    this.name = name;
    this.transitions = transitions;
    this.compact = compact;
  }

  /// This formatter without the event types of the entities, for models with many entities or event types. The event
  /// types involved in the triggers are still shown on the relations.
  public ERDiagramFormatter compact() {
    return new ERDiagramFormatter(name, transitions, true);
  }

  public File formatToFile(String directory) throws IOException {
    File file = file(directory, "puml");
    Files.writeString(file.toPath(), format());
    return file;
  }

  public File formatToImage(String directory) throws IOException {
    File file = file(directory, "svg");
    try (var out = new FileOutputStream(file)) {
      new SourceStringReader(format()).outputImage(out, new FileFormatOption(FileFormat.SVG));
    }
    return file;
  }

  private File file(String directory, String suffix) {
    var file = new File(String.format("%s/%s.er.%s", directory, name, suffix));
    //noinspection ResultOfMethodCallIgnored
    file.getParentFile().mkdirs();
    return file;
  }

  private record Relation(EntityModel from, EntityModel to, String label) {}

  public String format() {
    // Entities triggered by the model without being part of it are included too, with the event types triggered on them
    Map<EntityModel, Set<EventType<?, ?>>> eventTypes = new LinkedHashMap<>();
    Set<Relation> relations = new LinkedHashSet<>();
    transitions.entrySet().stream()
        .sorted(comparing(entry -> entry.getKey().name()))
        .forEach(entry -> {
          eventTypes.computeIfAbsent(entry.getKey(), _ -> new LinkedHashSet<>());
          entry.getValue().entrySet().stream()
              .sorted(comparing(e -> e.getKey().name()))
              .flatMap(e -> e.getValue().stream())
              .forEach(transition -> collect(entry.getKey(), transition, eventTypes, relations));
        });
    List<EntityModel> entities = new ArrayList<>(eventTypes.keySet());
    entities.sort(comparing(EntityModel::name));
    Map<EntityModel, String> aliases = new LinkedHashMap<>();
    entities.forEach(entity -> aliases.put(entity, "E" + (aliases.size() + 1)));
    return String.format(
        """
        @startuml
        skinparam backgroundColor transparent
        skinparam class {
          BackgroundColor #9bb08a
          BorderColor #4b5320
          ArrowColor #4b5320
        }
        hide circle
        hide empty members

        %s
        %s
        @enduml
        """,
        entities.stream()
            .map(entity -> entity(entity, aliases.get(entity), eventTypes.get(entity)))
            .collect(joining()),
        mergeParallelRelations(relations).stream()
            .map(r -> String.format("%s --> %s : %s\n", aliases.get(r.from()), aliases.get(r.to()), r.label()))
            .collect(joining())
    );
  }

  private void collect(
      EntityModel entity,
      TransitionModel<?, ?> transition,
      Map<EntityModel, Set<EventType<?, ?>>> eventTypes,
      Set<Relation> relations
  ) {
    eventTypes.computeIfAbsent(entity, _ -> new LinkedHashSet<>()).add(transition.eventType());
    collectTriggers(entity, transition.eventType(), transition.triggers(), false, eventTypes, relations);
    if (transition.reverseModel() != null)
      collectTriggers(entity, transition.eventType(), transition.reverseModel().triggers(), true, eventTypes, relations);
    for (var filter : transition.filters())
      collect(entity, filter.alternative().model(), eventTypes, relations);
  }

  private void collectTriggers(
      EntityModel entity,
      EventType<?, ?> eventType,
      List<EventTrigger<?, ?, ?>> triggers,
      boolean reversal,
      Map<EntityModel, Set<EventType<?, ?>>> eventTypes,
      Set<Relation> relations
  ) {
    for (EventTrigger<?, ?, ?> trigger : triggers) {
      EntityModel target = trigger.entityModel() != null ? trigger.entityModel() : entity;
      EventType<?, ?> triggered = trigger.eventSpec().eventType();
      eventTypes.computeIfAbsent(target, _ -> new LinkedHashSet<>()).add(triggered);
      String label = eventType.name() + " ↪ " + triggered.name();
      relations.add(new Relation(entity, target, reversal ? "<color:#7a0000>" + label + "</color>" : label));
    }
  }

  // Combines the relations between the same pair of entities into one arrow with the labels stacked, as parallel
  // arrows make the diagram hard to read.
  private static List<Relation> mergeParallelRelations(Set<Relation> relations) {
    Map<List<EntityModel>, List<String>> labels = new LinkedHashMap<>();
    for (Relation relation : relations)
      labels.computeIfAbsent(List.of(relation.from(), relation.to()), _ -> new ArrayList<>()).add(relation.label());
    return labels.entrySet().stream()
        .map(e -> new Relation(e.getKey().get(0), e.getKey().get(1), String.join("\\n", e.getValue())))
        .toList();
  }

  private String entity(EntityModel entity, String alias, Set<EventType<?, ?>> eventTypes) {
    String declaration = String.format(
        "entity \"%s\" as %s%s",
        entity.name(),
        alias,
        entity instanceof IdEntityModel ? " <<id>>" : entity instanceof IndexEntityModel ? " <<index>>" : ""
    );
    // An entity without a body would make PlantUML take it as a participant of a sequence diagram
    if (compact) return declaration + " {\n}\n";
    return String.format(
        "%s {\n%s}\n",
        declaration,
        eventTypes.stream()
            .map(eventType -> "  {field} " + eventType.name() + dataTypes(eventType) + "\n")
            .collect(joining())
    );
  }

  private static String dataTypes(EventType<?, ?> eventType) {
    if (eventType.inputDataType().equals(DataType.none()) && eventType.outputDataType().equals(DataType.none()))
      return "";
    return " : " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name();
  }

}
