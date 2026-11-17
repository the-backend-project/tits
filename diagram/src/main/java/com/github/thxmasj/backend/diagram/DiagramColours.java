package com.github.thxmasj.backend.diagram;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.backend.core.Severity;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.stream.Stream;

/// The colours of the text in the diagrams which is coloured, and how the diagrams are drawn in dark mode.
///
/// A diagram is shown in an `object` element, and a browser draws an embedded document of another colour scheme than
/// the page on an opaque background. The diagrams therefore take the colour scheme of the page, and in dark mode draw
/// their arrows, and the text on them, in light colours.
final class DiagramColours {

  private DiagramColours() {}

  /// A colour of text, in light and dark mode. The light one is used in the PlantUML source, which writes it in
  /// uppercase in the SVG.
  record Colour(String light, String dark) {}

  /// The event of a transition which reverses another, in the ER diagram
  static final Colour REVERSAL = new Colour("#7A0000", "#FF8A80");

  // Yellow can't be read on the light page, so a warning is dark amber in light mode
  private static final Map<Severity, Colour> SEVERITIES = Map.of(
      Severity.Warning, new Colour("#7A5200", "#F2C94C"),
      Severity.Error, new Colour("#C45200", "#FF9640"),
      Severity.Critical, new Colour("#B00000", "#FF5C5C")
  );

  /// The colour of the name of an event accepted with the severity, or null for [Severity#Info], whose events are
  /// named in the default colour
  static Colour of(Severity severity) {
    return SEVERITIES.get(severity);
  }

  private static final String ARROW = "#a3b06a";
  private static final String TEXT = "#e3e8d0";

  /// CSS for the `style` element of a diagram's SVG
  static final String STYLE = """
      svg{color-scheme:light dark}
      @media (prefers-color-scheme:dark){
      g.link path,g.start_entity ellipse{stroke:%1$s!important}
      g.link polygon,g.start_entity ellipse{fill:%1$s}
      g.link text[fill="#000"]{fill:%2$s}
      """.formatted(ARROW, TEXT)
      + Stream.concat(Stream.of(REVERSAL), SEVERITIES.values().stream())
          .map(c -> "g.link text[fill=\"" + c.light() + "\"]{fill:" + c.dark() + "}\n")
          .sorted()
          .collect(joining())
      + "}\n";

  /// Text outside the nodes' boxes in a diagram drawn in Graphviz, such as a heading above a box, which would be dark on
  /// the dark page. It is the dark primary colour of the page in light mode.
  static final Colour HEADING = new Colour("#353b16", TEXT);

  /// CSS for the `style` element of an SVG written by Graphviz, whose edges are groups of class `edge` and nodes groups
  /// of class `node`
  static final String GRAPHVIZ_STYLE = """
      svg{color-scheme:light dark}
      @media (prefers-color-scheme:dark){
      g.edge path{stroke:%1$s}
      g.edge polygon{fill:%1$s;stroke:%1$s}
      g.edge text{fill:%2$s}
      g.node text[fill="%3$s"]{fill:%4$s}
      }
      """.formatted(ARROW, TEXT, HEADING.light(), HEADING.dark());

  /// Adds [#GRAPHVIZ_STYLE] to an SVG written by Graphviz, which has no `defs` element
  static String addGraphvizStyle(String svg) {
    return svg.replaceFirst("(<svg[^>]*>)", "$1<style type=\"text/css\">" + Matcher.quoteReplacement(GRAPHVIZ_STYLE) + "</style>");
  }

  /// Adds [#STYLE] to an SVG written by PlantUML
  static String addStyle(String svg) {
    return svg.replaceFirst("<defs/>", "<defs></defs>")
        .replaceFirst("<defs>", "<defs><style type=\"text/css\">" + Matcher.quoteReplacement(STYLE) + "</style>");
  }
}
