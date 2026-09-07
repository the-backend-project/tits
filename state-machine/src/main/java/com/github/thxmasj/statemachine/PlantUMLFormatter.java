package com.github.thxmasj.statemachine;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class PlantUMLFormatter {

  private final static String STATE_BEGIN = "Begin"; // A bit bad to hard code the "Begin" name, as it is by convention only.
  private final EntityModel model;
  private final Traverser traverser;
  Map<State, List<TransitionModel<?, ?>>> transitions;
  private final boolean hideBuiltin;

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    this(model, transitions, true);
  }

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions, boolean hideBuiltin) {
    this.model = model;
    this.hideBuiltin = hideBuiltin;
    this.traverser = new Traverser(transitions);
    this.transitions = transitions;
  }

  public File formatToFile(String directory) throws IOException {
    File file = file(directory, "puml");
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
      writer.write(format());
    }
    return file;
  }

  public File formatToImage(String directory) throws IOException {
    File file = file(directory, "svg");
    new SourceStringReader(format()).outputImage(new FileOutputStream(file), new FileFormatOption(FileFormat.SVG));
    return file;
  }

  private File file(String directory, String suffix) {
    var file = new File(String.format("%s/%s.%s.%s", directory, model.getClass().getPackageName(), model.name(), suffix));
    //noinspection ResultOfMethodCallIgnored
    file.getParentFile().mkdirs();
    return file;
  }

  public String format() {
    return String.format(
      """
      @startuml
      !pragma svginteractive true
      
      %s
      %s

      %s
  
      @enduml
      """,
      // States
      transitions.keySet().stream().map(this::state).collect(joining("\n")),
      // Choices
      choices(model.initialState(), new HashSet<>()),
      // Transitions
      transitions(model.initialState(), new HashSet<>())
    );
  }

  AtomicInteger choiceCounter1 = new AtomicInteger();
  AtomicInteger choiceCounter2 = new AtomicInteger();

  private String choices(State state, Set<State> visited) {
    if (visited.contains(state)) return "";
    visited.add(state);
    StringBuilder s = new StringBuilder();
    List<TransitionModel<?, ?>> transitionsForState = this.transitions.get(state);
    if (transitionsForState == null) throw new IllegalStateException(state.name());
    for (TransitionModel<?, ?> transition : transitionsForState) {
      var targetState = traverser.targetState(state, transition);
      if (!transition.filters().isEmpty()) {
        s.append(String.format("state Choice%d <<choice>>\n",  choiceCounter1.incrementAndGet()));
      }
      s.append(choices(targetState, visited));
    }
    return s.toString();
  }

  private String transitions(State state, Set<State> visited) {
    if (visited.contains(state)) return "";
    visited.add(state);
    StringBuilder s = new StringBuilder();
    List<TransitionModel<?, ?>> transitionsForState = this.transitions.get(state);
    if (transitionsForState == null) throw new IllegalStateException(state.name());
    for (TransitionModel<?, ?> transition : transitionsForState) {
      if (hideBuiltin && BuiltinEventTypes.ALL.contains(transition.eventType())) continue;
      State targetState = traverser.targetState(state, transition);
      if (!transition.filters().isEmpty()) {
        String choiceName = "Choice" + choiceCounter2.incrementAndGet(); //choiceName(state, transition);
        s.append(String.format("%s -down-> %s: %s\n", state.name(), choiceName, transition.eventType().name()));
        for (var filter : transition.filters()) {
          s.append(transition(choiceName, traverser.targetState(state, filter.alternative().model()), filter.alternative().model().eventType(), filter.alternative().model()));
        }
      } else {
        s.append(transition(state.name(), targetState, transition.eventType(), transition));
      }
      s.append(transitions(targetState, visited));
    }
    return s.toString();
  }

  private String transition(String source, State targetState, EventType<?, ?> eventType, TransitionModel<?, ?> transition) {
    return String.format(
        "%s --> %s: %s\n",
        /*state.state().name().equals(STATE_BEGIN) ? "[*]" :*/ source,
        targetState.name(),
        Stream.of(
            String.format("%s", eventType.name()),
            "f: " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name(),
            transition.triggers().stream().map(t -> "<&share>" + t.eventSpec().eventType().name() + "@" + t.entityModel().name()).collect(joining("\\n"))
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String state(State state) {
    if (state.name().equals(STATE_BEGIN)) return "";
    return String.format(
        """
        state %s: %s
        """,
        state.name(),
        Stream.of(
            Optional.ofNullable(state.timeout() == State.NEVER_TIMEOUT ? null : state.timeout()).map(timeout -> "timeout: " + timeout.eventType().name() + " after " + timeout.duration().toString()).orElse("")
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String conditionalState(State state) {
    return String.format(
        """
        state %s <<choice>>
        """,
        state.name()
    );
  }

}
