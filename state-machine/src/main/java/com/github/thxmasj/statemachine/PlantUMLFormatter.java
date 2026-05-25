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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class PlantUMLFormatter {

  private final static String STATE_BEGIN = "Begin"; // A bit bad to hard code the "Begin" name, as it is by convention only.
  private final TraversableState beginState;
  private final EntityModel model;
  private final boolean hideBuiltin;

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    this(model, transitions, true);
  }

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions, boolean hideBuiltin) {
    this.model = model;
    this.hideBuiltin = hideBuiltin;
    this.beginState = TraversableState.create(model, transitions);
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
  
      @enduml
      """,
      states(beginState, new HashSet<>()),
      transitions(beginState, new HashSet<>())
    );
  }

  private String choiceName(TraversableState state, TransitionModel<?, ?> transition) {
    return state.state().name() + "_" + transition.eventType().name();
  }

  private String states(TraversableState state, Set<State> visited) {
    if (visited.contains(state.state())) return "";
    visited.add(state.state());
//    StringBuilder s = new StringBuilder(state.state().isChoice() ? conditionalState(state.state()) : state(state.state()));
    StringBuilder s = new StringBuilder(state(state.state()));
    for (var t : state.forwardTransitions()) {
      if (!t.filters().isEmpty())
        s.append(String.format(
            """
            state %s <<choice>>
            """,
            choiceName(state, t)
        ));
    }
    for (TraversableState targetState : state.targetStates()) {
      s.append(states(targetState, visited));
    }
    return s.toString();
  }

  private String transitions(TraversableState state, Set<State> visited) {
    if (visited.contains(state.state())) return "";
    visited.add(state.state());
    StringBuilder s = new StringBuilder();
    for (TransitionModel<?, ?> transition : state.forwardTransitions()) {
      if (hideBuiltin && BuiltinEventTypes.ALL.contains(transition.eventType())) continue;
      var targetState = state.forward(transition.eventType());
      if (!transition.filters().isEmpty()) {
        String choiceName = choiceName(state, transition);
        s.append(String.format("%s --> %s: %s\n", state.state().name(), choiceName, transition.eventType().name()));
        for (var filter : transition.filters()) {
          s.append(transition(choiceName, targetState, filter.alternative().model().eventType(), transition));
        }
      } else {
        s.append(transition(state.state().name(), targetState, transition.eventType(), transition));
      }
      s.append(transitions(targetState, visited));
    }
    return s.toString();
  }

  private String transition(String source, TraversableState targetState, EventType<?, ?> eventType, TransitionModel<?, ?> transition) {
    return String.format(
        "%s --> %s: %s\n",
        /*state.state().name().equals(STATE_BEGIN) ? "[*]" :*/ source,
        targetState.state().name(),
        Stream.of(
            String.format("%s", eventType.name()),
            "I:" + eventType.inputDataType().name() + "/" + "O:" + eventType.outputDataType().name(),
            transition.outgoingRequests().stream().map(ns -> outgoingRequest(ns, false)).collect(joining("\\n")),
            transition.reverseModel() != null ? transition.reverseModel()
                .outgoingRequests()
                .stream()
                .map(ns -> outgoingRequest(ns, true))
                .collect(joining("\\n")) : ""
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String outgoingRequest(OutgoingRequestModel<?, ?> spec, boolean reverse) {
    return String.format(
        "<color:" + (reverse ? "red" : "blue") + ">%s %s %s</color>",
        spec.creatorType() != null ? spec.creatorType().getSimpleName() :
            spec.creator().name(),
        spec.guaranteed() ? "&#8658;" : "&#8594;",
        spec.queue()
    );
  }

  private String outgoingResponse(OutgoingResponseModel<?, ?> spec, boolean reverse) {
    return String.format(
        "<color:" + (reverse ? "red" : "blue") + ">&#8592; %s</color>",
        Objects.requireNonNullElseGet(spec.creatorType(), () -> spec.creator().getClass()).getSimpleName()
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
            Optional.ofNullable(state.timeout() == State.NEVER_TIMEOUT ? null : state.timeout()).map(timeout -> "timeout: " + timeout.event().eventType().name() + " after " + timeout.duration().toString()).orElse("")
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
