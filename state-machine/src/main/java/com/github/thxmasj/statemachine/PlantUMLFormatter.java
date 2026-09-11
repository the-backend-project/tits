package com.github.thxmasj.statemachine;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class PlantUMLFormatter {

  private final static String STATE_BEGIN = "Begin"; // A bit bad to hard code the "Begin" name, as it is by convention only.
  private final EntityModel model;
  Map<String, List<TransitionModel<?, ?>>> transitions;

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    this.model = model;
    this.transitions = transitions.entrySet().stream().collect(toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
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
      transitions.keySet().stream().filter(state -> !state.equals("Intermediate")).map(this::state).collect(joining("\n")),
      // Choices
      choices(model.initialState().name(), new HashSet<>()),
      // Transitions
        formatTransitionsFrom(
            model.initialState().name(),
            this.transitions.get(model.initialState().name()),
            new HashSet<>()
        )
    );
  }

  AtomicInteger choiceCounter1 = new AtomicInteger();
  AtomicInteger choiceCounter2 = new AtomicInteger();

  private String choices(String state, Set<String> visited) {
    if (visited.contains(state)) return "";
    visited.add(state);
    StringBuilder s = new StringBuilder();
    List<TransitionModel<?, ?>> transitionsForState = this.transitions.get(state);
    if (transitionsForState == null) throw new IllegalStateException(state);
    for (TransitionModel<?, ?> transition : transitionsForState) {
      s.append(traverseForChoices(transition, visited));
    }
    return s.toString();
  }

  private String traverseForChoices(TransitionModel<?, ?> transition, Set<String> visited) {
    StringBuilder s = new StringBuilder();
    if (transition.toState() == null)
      // Self transitions can't have choices
      return "";
    String targetState = transition.toState().name();
    if (!transition.filters().isEmpty()) {
      s.append(String.format("state Choice%d <<choice>>\n", choiceCounter1.incrementAndGet()));
      for (var filter : transition.filters()) {
        s.append(traverseForChoices(filter.alternative().model(), visited));
      }
    } else {
      s.append(choices(targetState, visited));
    }
    return s.toString();
  }

  private String formatTransitionsFrom(
      String sourceState,
      List<TransitionModel<?, ?>> transitionsFromState,
      Set<String> visited
  ) {
    if (visited.contains(sourceState)) return "";
    visited.add(sourceState);
    System.out.println("Formatting transitions from " + sourceState);
    StringBuilder s = new StringBuilder();
    for (TransitionModel<?, ?> transition : transitionsFromState) {
      s.append(formatTransitionTree(sourceState, visited, transition));
    }
    return s.toString();
  }

  private String formatTransitionTree(String sourceState, Set<String> visited, TransitionModel<?, ?> transition) {
    StringBuilder s = new StringBuilder();
    String targetState = transition.toState() != null ? transition.toState().name() : sourceState;
    if (!transition.filters().isEmpty()) {
      System.out.println("Formatting transition with choice from " + sourceState);
      String choiceName = "Choice" + choiceCounter2.incrementAndGet(); //choiceName(state, transition);
//        s.append(String.format("%s -down-> %s: %s\n", state.name(), choiceName, transition.eventType().name()));
      s.append(formatTransition(sourceState, choiceName, transition.eventType(), transition));
      for (var filter : transition.filters()) {
        s.append(formatTransitionTree(choiceName, visited, filter.alternative().model()));
        //s.append(formatTransitionsFrom(choiceName, this.transitions.get(targetState), visited));
      }
    } else {
      s.append(formatTransition(sourceState, targetState, transition.eventType(), transition));
      if (!sourceState.equals(targetState))
        s.append(formatTransitionsFrom(targetState, this.transitions.get(targetState), visited));
    }
    return s.toString();
  }

  private String formatTransition(String sourceState, String targetState, EventType<?, ?> eventType, TransitionModel<?, ?> transition) {
    System.out.println("Formatting transition <" + sourceState + " --> " + targetState + ": " + eventType.name() + ">");
    return String.format(
        "%s %s %s: %s\n",
        sourceState.equals(STATE_BEGIN) ? "[*]" : sourceState,
        sourceState.equals(STATE_BEGIN) ? "-right->" : "-->",
        targetState,
        Stream.of(
            String.format("%s", eventType.name()),
            "f: " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name(),
            transition.triggers().stream().map(t -> "<&share>" + t.eventSpec().eventType().name() + "@" + t.entityModel().name()).collect(joining("\\n"))
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String state(String state) {
    if (state.equals(STATE_BEGIN)) return "";
    return String.format(
        """
        state %s
        """,
        state
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
