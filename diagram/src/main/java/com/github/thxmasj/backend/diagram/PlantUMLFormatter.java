package com.github.thxmasj.backend.diagram;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparingInt;
import static java.util.Optional.ofNullable;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

public class PlantUMLFormatter {

  private final EntityModel model;
  Map<State, List<TransitionModel<?, ?>>> transitions;

  public PlantUMLFormatter(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) {
    this.model = model;
    if (model instanceof IndexEntityModel m) {
      this.transitions = m.transitions().entrySet().stream().collect(toMap(entry -> entry.getKey(), Map.Entry::getValue));
    } else if (model instanceof IdEntityModel m) {
      this.transitions = m.transitions().entrySet().stream().collect(toMap(entry -> entry.getKey(), Map.Entry::getValue));
    } else {
      this.transitions = transitions.entrySet().stream().collect(toMap(entry -> entry.getKey(), Map.Entry::getValue));
    }
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
    Diagram diagram = diagram();
    var svg = new ByteArrayOutputStream();
    new SourceStringReader(diagram.source()).outputImage(svg, new FileFormatOption(FileFormat.SVG));
    try (var out = new FileOutputStream(file)) {
      out.write(addDetailPopups(svg.toString(UTF_8), diagram.labelsBySourceLine()).getBytes(UTF_8));
    }
    return file;
  }

  private static final Pattern LINK =
      Pattern.compile("<g class=\"link\"[^>]*? data-source-line=\"(\\d+)\"[^>]*>(.*?)</g>", Pattern.DOTALL);
  private static final Pattern TEXT = Pattern.compile("<text ([^>]*)>.*?</text>", Pattern.DOTALL);
  private static final String POPUP_STYLE = """
      <style type="text/css">
      text.has-details{text-decoration:underline dotted;cursor:help}
      .details{visibility:hidden;pointer-events:none;overflow:visible}
      .details>div{display:flex;justify-content:center}
      .details>div>div{padding:4px 8px;font:13px sans-serif;line-height:16px;text-align:center;white-space:nowrap;\
      background:#9bb08a;border:1px solid #4b5320;border-radius:4px;box-shadow:0 2px 6px rgba(0,0,0,.3)}
      %s
      </style>""";
  // Moves popups that would extend past the edges of the diagram, and places them above the event name if there is no
  // room below it.
  private static final String POPUP_SCRIPT = """
      <script><![CDATA[
      (function () {
        var svg = document.documentElement, view = svg.viewBox.baseVal;
        svg.querySelectorAll('.details').forEach(function (popup) {
          var box = popup.firstChild.firstChild, x = popup.x.baseVal.value, y = popup.y.baseVal.value;
          var left = x + box.offsetLeft, right = left + box.offsetWidth;
          if (left < 0) popup.setAttribute('x', x - left);
          else if (right > view.width) popup.setAttribute('x', x - (right - view.width));
          if (y + box.offsetHeight > view.height) popup.setAttribute('y', y - box.offsetHeight - 21);
        });
      })();
      ]]></script>""";

  // The diagram only shows the event names on the transitions. The rest of each label is put in a popup that is shown
  // while the mouse hovers over the event name. The popups are added last, so they are drawn on top of the diagram.
  private static String addDetailPopups(String svg, Map<Integer, List<Label>> labelsBySourceLine) {
    StringBuilder svgWithIds = new StringBuilder();
    StringBuilder popups = new StringBuilder();
    StringBuilder rules = new StringBuilder();
    int popupCount = 0;
    Matcher link = LINK.matcher(svg);
    while (link.find()) {
      List<Label> labels = labelsBySourceLine.getOrDefault(Integer.parseInt(link.group(1)), List.of());
      List<MatchResult> texts = TEXT.matcher(link.group()).results().toList();
      Map<MatchResult, String> ids = new HashMap<>();
      // Each label is drawn as a single text line with the event name
      for (int i = 0; i < labels.size() && i < texts.size(); i++) {
        Label label = labels.get(i);
        if (label.details().isEmpty()) continue;
        MatchResult name = texts.get(i);
        String id = "event" + ++popupCount;
        ids.put(name, id);
        double x = attribute(name.group(1), "x") + attribute(name.group(1), "textLength") / 2;
        double y = attribute(name.group(1), "y") + 4;
        popups.append(String.format(
            Locale.ROOT,
            "<foreignObject class=\"details\" id=\"%s-details\" x=\"%.1f\" y=\"%.1f\" width=\"1600\" height=\"400\">"
                + "<div xmlns=\"http://www.w3.org/1999/xhtml\"><div>%s</div></div></foreignObject>",
            id, x - 800, y, label.details().stream().map(PlantUMLFormatter::detailHtml).collect(joining("<br/>"))
        ));
        rules.append(String.format("svg:has(#%1$s:hover) #%1$s-details{visibility:visible}\n", id));
      }
      Matcher text = TEXT.matcher(link.group());
      StringBuilder linkWithIds = new StringBuilder();
      for (MatchResult match : texts) {
        text.find();
        String id = ids.get(match);
        text.appendReplacement(linkWithIds, Matcher.quoteReplacement(id == null
            ? match.group()
            : "<text id=\"" + id + "\" class=\"has-details\" " + match.group().substring("<text ".length())
        ));
      }
      text.appendTail(linkWithIds);
      link.appendReplacement(svgWithIds, Matcher.quoteReplacement(linkWithIds.toString()));
    }
    link.appendTail(svgWithIds);
    String result = svgWithIds.toString()
        .replaceFirst("<defs>", Matcher.quoteReplacement("<defs>" + String.format(POPUP_STYLE, rules)));
    int end = result.lastIndexOf("</g></svg>");
    return result.substring(0, end) + popups + "</g>" + POPUP_SCRIPT + "</svg>";
  }

  private static double attribute(String attributes, String name) {
    Matcher matcher = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attributes);
    return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0;
  }

  private static String detailHtml(String detail) {
    String html = detail
        .replace("<&share>", "\u21aa ").replace("<&bolt>", "\u26a1 ")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return html.contains("&lt;color:red&gt;")
        ? "<span style=\"color:#7a0000\">" + html.replace("&lt;color:red&gt;", "") + "</span>"
        : html;
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
    return diagram().source();
  }

  private record Label(String eventName, List<String> details) {}

  private record Arrow(String arrow, List<Label> labels) {}

  private record Diagram(String source, Map<Integer, List<Label>> labelsBySourceLine) {}

  private Diagram diagram() {
    List<Arrow> arrows = mergeParallelTransitions(formatTransitionsFrom(
        Begin,
        this.transitions.get(Begin),
        new HashSet<>()
    ));
    String transitionLines = arrows.stream()
        .map(arrow -> arrow.arrow() + ": " + arrow.labels().stream().map(Label::eventName).collect(joining("\\n")))
        .collect(joining("\n", "", "\n"));
    String source = String.format(
      """
      @startuml
      !pragma svginteractive true
      skinparam backgroundColor transparent
      skinparam state {
        BackgroundColor #9bb08a
        BorderColor #4b5320
      }
      skinparam ActivityDiamondBackgroundColor #9bb08a
      skinparam ActivityDiamondBorderColor #4b5320

      %s
      %s

      %s
  
      @enduml
      """,
      // States, declared in the order the transitions reach them, as the layout depends on the declaration order
      transitions.keySet().stream()
          .filter(state -> !state.name().equals("Intermediate"))
          .sorted(comparingInt(state -> firstOccurrence(transitionLines, state)))
          .map(state -> state(state))
          .collect(joining("\n")),
      // Choices
      choices(Begin, new HashSet<>()),
      // Transitions
      transitionLines
    );
    // PlantUML numbers the source lines from 0
    int firstTransitionLine = (int) source.substring(0, source.indexOf(transitionLines)).chars().filter(c -> c == '\n').count();
    Map<Integer, List<Label>> labelsBySourceLine = new HashMap<>();
    for (int i = 0; i < arrows.size(); i++) {
      labelsBySourceLine.put(firstTransitionLine + i, arrows.get(i).labels());
    }
    return new Diagram(source, labelsBySourceLine);
  }

  private int firstOccurrence(String transitionLines, State state) {
    int index = transitionLines.indexOf(stateId(state));
    return index < 0 ? Integer.MAX_VALUE : index;
  }

  // Combines transitions between the same pair of states into one arrow with the event names stacked, as side-by-side
  // parallel arrows make the diagram very wide.
  private List<Arrow> mergeParallelTransitions(String transitions) {
    Map<String, Arrow> arrows = new LinkedHashMap<>();
    for (String line : transitions.split("\n")) {
      if (line.isBlank()) continue;
      String[] arrowAndLabel = line.split(": ", 2);
      String[] parts = arrowAndLabel[0].split(" ");
      String[] labelLines = arrowAndLabel[1].split("\\\\n");
      // Self transitions are drawn as loops, which don't widen the diagram
      String key = parts[0].equals(parts[2]) ? line : arrowAndLabel[0];
      arrows.computeIfAbsent(key, _ -> new Arrow(arrowAndLabel[0], new ArrayList<>())).labels().add(
          new Label(labelLines[0], List.of(labelLines).subList(1, labelLines.length))
      );
    }
    return List.copyOf(arrows.values());
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
      s.append(formatTransitionTree(sourceState, sourceState, visited, transition));
    }
    return s.toString();
  }

  private record Choice(String name) implements State {}

  // ownerState is the state the transition originates from, while sourceState is either that state or a choice
  // between it and the target. Self transitions go back to ownerState, never to a choice.
  private String formatTransitionTree(State ownerState, State sourceState, Set<State> visited, TransitionModel<?, ?> transition) {
    StringBuilder s = new StringBuilder();
    State targetState = transition.toState() != null ? transition.toState() : ownerState;
    if (!transition.filters().isEmpty()) {
      System.out.println("Formatting transition with choice from " + sourceState);
      String choiceName = "Choice" + choiceCounter2.incrementAndGet();
      State choiceState = new Choice(choiceName); //choiceName(state, transition);
//        s.append(String.format("%s -down-> %s: %s\n", state.name(), choiceName, transition.eventType().name()));
      s.append(formatTransition(sourceState, choiceState, transition.eventType(), transition));
      for (var filter : transition.filters()) {
        s.append(formatTransitionTree(ownerState, choiceState, visited, filter.alternative().model()));
        //s.append(formatTransitionsFrom(choiceName, this.transitions.get(targetState), visited));
      }
    } else {
      s.append(formatTransition(sourceState, targetState, transition.eventType(), transition));
      if (!ownerState.equals(targetState))
        s.append(formatTransitionsFrom(targetState, this.transitions.get(targetState), visited));
    }
    return s.toString();
  }

  private String formatTransition(State sourceState, State targetState, EventType<?, ?> eventType, TransitionModel<?, ?> transition) {
    System.out.println("Formatting transition <" + sourceState + " --> " + targetState + ": " + eventType.name() + ">");
    return String.format(
        "%s %s %s: %s\n",
        // Self transitions in Begin are loops on its state, as the initial pseudo state can't have loops
        sourceState.equals(Begin) && !targetState.equals(Begin) ? "[*]" : stateId(sourceState),
        sourceState.equals(Begin) && !targetState.equals(Begin) ? "-right->" : "-->",
        stateId(targetState),
        Stream.of(
            String.format("%s", eventType.name()),
            eventType.inputDataType().equals(DataType.none()) && eventType.outputDataType().equals(DataType.none()) ? "" : "f: " + eventType.inputDataType().name() + " → " + eventType.outputDataType().name(),
            transition.triggers().stream().map(t -> "<&share>" + t.eventSpec().eventType().name() + "@" + ofNullable(t.entityModel()).map(m -> m.name()).orElse("???")).collect(joining("\\n")),
            transition.actions().stream().map(a -> "<&bolt>" + a.name()).collect(joining("\\n")),
            transition.reverseModel() != null ? transition.reverseModel().triggers().stream().map(t -> "<&share><color:red>" + t.eventSpec().eventType().name() + "@" + ofNullable(t.entityModel()).map(m -> m.name()).orElse("???")).collect(joining("\\n")) : ""
        ).filter(not(String::isEmpty)).collect(joining("\\n"))
    );
  }

  private String state(State state) {
    if (state.equals(Begin)) {
      if (transitions.get(Begin).stream().noneMatch(transition -> transition.toState() == null)) return "";
      // Declared for its self transitions, e.g. the events of id entities
      return String.format(
          """
          state "%s" as %s
          [*] -right-> %2$s
          """,
          state.name(),
          stateId(state)
      );
    }
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
