package com.github.thxmasj.backend.diagram;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/// A diagram drawn by hand in the DOT language of Graphviz, for what isn't an entity model, such as how the event logs
/// of several entities relate. It is rendered with `dot`, which must be on the path, into an SVG which takes the colour
/// scheme of the page like the other diagrams: in dark mode, its edges and their labels are drawn in light colours.
public final class GraphvizFormatter {

  private final String source;

  public GraphvizFormatter(String source) {
    this.source = source;
  }

  /// Renders the diagram into the SVG file, and returns it
  public File formatToImage(File file) throws IOException {
    var dot = new ProcessBuilder("dot", "-Tsvg").redirectError(ProcessBuilder.Redirect.INHERIT).start();
    try (var input = dot.getOutputStream()) {
      input.write(source.getBytes(UTF_8));
    }
    String svg;
    try (var output = dot.getInputStream()) {
      svg = new String(output.readAllBytes(), UTF_8);
    }
    try {
      if (dot.waitFor() != 0)
        throw new IOException("dot failed with exit code " + dot.exitValue());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for dot", e);
    }
    Files.writeString(file.toPath(), DiagramColours.addGraphvizStyle(svg));
    return file;
  }

}
