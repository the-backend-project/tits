package com.github.thxmasj.statemachine;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class PlantUMLFormatter {

  private final static String STATE_BEGIN = "Begin"; // A bit bad to hard code the "Begin" name, as it is by convention only.
  private final TraversableState beginState;
  private final EntityModel model;
  private final boolean hideBuiltin;

  public PlantUMLFormatter(EntityModel model) {
    this(model, true);
  }

  public PlantUMLFormatter(EntityModel model, boolean hideBuiltin) {
    this.model = model;
    this.hideBuiltin = hideBuiltin;
    this.beginState = TraversableState.create(model);
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
    return new File(String.format("%s/%s.%s.%s", directory, model.getClass().getPackageName(), model.name(), suffix));
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

  private String states(TraversableState state, Set<State> visited) {
    if (visited.contains(state.state())) return "";
    visited.add(state.state());
    StringBuilder s = new StringBuilder(state.state().isChoice() ? conditionalState(state.state()) : state(state.state()));
    for (TraversableState targetState : state.targetStates()) {
      s.append(states(targetState, visited));
    }
    return s.toString();
  }

  private String transitions(TraversableState state, Set<State> visited) {
    if (visited.contains(state.state())) return "";
    visited.add(state.state());
    StringBuilder s = new StringBuilder();
    for (var transition : state.forwardTransitions()) {
      if (hideBuiltin && transition.eventType() instanceof BuiltinEventTypes) continue;
      var targetState = state.forward(transition.eventType());
      s.append(String.format(
          "%s --> %s: %s\n",
          /*state.state().name().equals(STATE_BEGIN) ? "[*]" :*/ state.state().name(),
          targetState.state().name(),
          Stream.of(
              transition.eventType().name(),
              transition.outgoingRequests().stream().map(ns -> outgoingRequest(ns, false)).collect(joining("\\n")),
              transition.outgoingResponses().stream().map(ns -> outgoingResponse(ns, false)).collect(joining("\\n")),
              transition.reverse() != null ? transition.reverse().outgoingRequests().stream().map(ns -> outgoingRequest(ns, true)).collect(joining("\\n")) : ""
          ).filter(not(String::isEmpty)).collect(joining("\\n"))
      ));
      s.append(transitions(targetState, visited));
    }
    return s.toString();
  }

  private String outgoingRequest(OutgoingRequestModel<?, ?> spec, boolean reverse) {
    return String.format(
        "<color:" + (reverse ? "red" : "blue") + ">%s %s %s</color>%s",
        Objects.requireNonNullElseGet(spec.notificationCreatorType(), () -> spec.notificationCreator().getClass())
            .getSimpleName(),
        spec.guaranteed() ? ">>" : ">",
        spec.subscriber(),
        spec.responseValidator() != null ? " > <color:green>" + spec.responseValidator().getClass().getSimpleName() + "</color>" : ""
    );
  }

  private String outgoingResponse(OutgoingResponseModel<?, ?> spec, boolean reverse) {
    return String.format(
        "<color:" + (reverse ? "red" : "blue") + ">%s %s %s</color>",
        Objects.requireNonNullElseGet(spec.creatorType(), () -> spec.creator().getClass()).getSimpleName(),
        ">",
        "client"
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
            Optional.of(state.actions().stream().map(Class::getSimpleName).collect(joining(", ")))
                .filter(not(String::isEmpty))
                .map(s -> "action: " + s)
                .orElse(""),
            state.timeout().map(timeout -> "timeout: " + timeout.eventType() + " after " + timeout.duration().toString()).orElse("")
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
