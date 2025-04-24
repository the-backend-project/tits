package com.github.thxmasj.statemachine;

import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;

import java.io.BufferedWriter;
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

  public PlantUMLFormatter(EntityModel model) {
    this.beginState = TraversableState.create(model);
  }

  public void formatToFile(String directory) throws IOException {
    String fileName = String.format(
        "%s/%s.puml",
        directory, beginState.state().getClass().getName().replace("$1", "")
    );
    try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
      writer.write(format());
    }
    System.out.println("Wrote <" + fileName + ">");
  }

  public String format() {
    return String.format(
      """
      @startuml
      hide empty description
  
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
      var targetState = state.forward(transition.eventType());
      s.append(String.format(
          "%s --> %s: %s\n",
          /*state.state().name().equals(STATE_BEGIN) ? "[*]" :*/ state.state().name(),
          targetState.state().name(),
          Stream.of(
              transition.eventType().name(),
              transition.outgoingRequests().stream().map(ns -> notification(ns, false)).collect(joining("\\n")),
              transition.reverse() != null ? transition.reverse().outgoingRequests().stream().map(ns -> notification(ns, true)).collect(joining("\\n")) : ""
          ).filter(not(String::isEmpty)).collect(joining("\\n"))
      ));
      s.append(transitions(targetState, visited));
    }
    return s.toString();
  }

  private String notification(OutgoingRequestModel<?, ?> spec, boolean reverse) {
    return String.format(
        "<color:" + (reverse ? "red" : "blue") + ">%s %s %s</color>%s",
        Objects.requireNonNullElseGet(spec.notificationCreatorType(), () -> spec.notificationCreator().getClass())
            .getSimpleName(),
        spec.guaranteed() ? ">>" : ">",
        spec.subscriber(),
        spec.responseValidator() != null ? " > <color:green>" + spec.responseValidator().getClass().getSimpleName() + "</color>" : ""
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
