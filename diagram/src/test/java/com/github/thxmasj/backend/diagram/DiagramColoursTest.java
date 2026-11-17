package com.github.thxmasj.backend.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DiagramColoursTest {

  /// An SVG written by Graphviz has no `defs` element, so the style goes first in the `svg` element
  @Test
  void graphvizStyleIsAddedFirstInTheSvgElement() {
    var svg = """
        <svg width="100pt" height="50pt" viewBox="0 0 100 50" xmlns="http://www.w3.org/2000/svg">
        <g id="graph0" class="graph"><g class="edge"><path d="M0,0"/></g></g>
        </svg>
        """;
    var styled = DiagramColours.addGraphvizStyle(svg);
    assertThat(styled).contains(
        "xmlns=\"http://www.w3.org/2000/svg\"><style type=\"text/css\">" + DiagramColours.GRAPHVIZ_STYLE + "</style>\n<g id=\"graph0\""
    );
    assertThat(DiagramColours.GRAPHVIZ_STYLE).contains("prefers-color-scheme:dark", "g.edge path{stroke:");
  }

}
