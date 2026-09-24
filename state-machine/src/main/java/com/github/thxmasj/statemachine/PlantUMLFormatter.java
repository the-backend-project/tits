package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static java.util.Optional.ofNullable;
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

  private final EntityModel model;
  Map<State, List<TransitionModel<?, ?>>> transitions;

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    this.model = model;
    this.transitions = transitions.entrySet().stream().collect(toMap(entry -> entry.getKey(), Map.Entry::getValue));
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

  private String stateId(State state) {
    return state instanceof Choice ? state.name() : state.name()+state.hashCode();
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
      transitions.keySet().stream().filter(state -> !state.name().equals("Intermediate")).map(state -> state(state)).collect(joining("\n")),
      // Choices
      choices(Begin, new HashSet<>()),
      // Transitions
        formatTransitionsFrom(
            Begin,
            this.transitions.get(Begin),
            new HashSet<>()
        )
    );
  }

  AtomicInteger choiceCounter1 = new AtomicInteger();
  AtomicInteger choiceCounter2 = new AtomicInteger();

  private String choices(State state, Set<State> visited) {
    if (visited.contains(state)) return "";
    visited.add(state);
    StringBuilder s = new StringBuilder();
    List<TransitionModel<?, ?>> transitionsForState = this.transitions.get(state);
    if (transitionsForState == null) throw new IllegalStateException(state.toString());
    for (TransitionModel<?, ?> transition : transitionsForState) {
      s.append(traverseForChoices(transition, visited));
    }
    return s.toString();
  }

  private String traverseForChoices(TransitionModel<?, ?> transition, Set<State> visited) {
    StringBuilder s = new StringBuilder();
    if (transition.toState() == null)
      // Self transitions can't have choices
      return "";
    State targetState = transition.toState();
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
      State sourceState,
      List<TransitionModel<?, ?>> transitionsFromState,
      Set<State> visited
  ) {
    if (visited.contains(sourceState)) return "";
    visited.add(sourceState);
    System.out.println("Formatting transitions from " + sourceState.name());
    StringBuilder s = new StringBuilder();
    for (TransitionModel<?, ?> transition : transitionsFromState) {
      s.append(formatTransitionTree(sourceState, visited, transition));
    }
    return s.toString();
  }

  private record Choice(String name) implements State {}

  private String formatTransitionTree(State sourceState, Set<State> visited, TransitionModel<?, ?> transition) {
    StringBuilder s = new StringBuilder();
    State targetState = transition.toState() != null ? transition.toState() : sourceState;
    if (!transition.filters().isEmpty()) {
      System.out.println("Formatting transition with choice from " + sourceState);
      String choiceName = "Choice" + choiceCounter2.incrementAndGet();
      State choiceState = new Choice(choiceName); //choiceName(state, transition);
//        s.append(String.format("%s -down-> %s: %s\n", state.name(), choiceName, transition.eventType().name()));
      s.append(formatTransition(sourceState, choiceState, transition.eventType(), transition));
      for (var filter : transition.filters()) {
        s.append(formatTransitionTree(choiceState, visited, filter.alternative().model()));
        //s.append(formatTransitionsFrom(choiceName, this.transitions.get(targetState), visited));
      }
    } else {
      s.append(formatTransition(sourceState, targetState, transition.eventType(), transition));
      if (!sourceState.equals(targetState))
        s.append(formatTransitionsFrom(targetState, this.transitions.get(targetState), visited));
    }
    return s.toString();
  }

  private String formatTransition(State sourceState, State targetState, EventType<?, ?> eventType, TransitionModel<?, ?> transition) {
    System.out.println("Formatting transition <" + sourceState + " --> " + targetState + ": " + eventType.name() + ">");
    return String.format(
        "%s %s %s: %s\n",
        sourceState.equals(Begin) ? "[*]" : stateId(sourceState),
        sourceState.equals(Begin) ? "-right->" : "-->",
        stateId(targetState),
        Stream.of(
            String.format("%s", eventType.name()),
            eventType.inputDataType().equals(DataType.none()) && eventType.outputDataType().equals(DataType.none()) ? "" : "f: " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name(),
            transition.triggers().stream().map(t -> "<&share>" + t.eventSpec().eventType().name() + "@" + ofNullable(t.entityModel()).map(m -> m.name()).orElse("???")).collect(joining("\\n"))
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String state(State state) {
    if (state.equals(Begin)) return "";
    return String.format(
        """
        state "%s" as %s
        """,
        state.name(),
        stateId(state)
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
