package com.github.thxmasj.backend.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/// The debug output of the libraries, on stdout.
///
/// The output holds data the libraries have no control of, such as the request an entity is triggered with. Every
/// line after the first of a text is therefore marked, so that data can't make up a line of its own in the output,
/// e.g. one passing for an event written by [JsonLinesListener]. The first line is to begin with words of the
/// library.
public final class Debug {

  private static final String MARK = "  | ";

  private Debug() {}

  public static void log(String text) {
    System.out.println(marked(text));
  }

  /// Writes the text followed by the stack trace of the failure
  public static void log(String text, Throwable failure) {
    var trace = new StringWriter();
    failure.printStackTrace(new PrintWriter(trace));
    log(text + "\n" + trace);
  }

  /// The text with every line after its first marked, and the first one too unless it begins with a letter or a
  /// digit, which no JSON object does. A line ends with a line feed, a carriage return or both, which is what the
  /// readers of the output take for line breaks.
  static String marked(String text) {
    List<String> lines = text.lines().toList();
    var marked = new StringBuilder();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (i > 0)
        marked.append('\n');
      if (i > 0 || !(line.isEmpty() || Character.isLetterOrDigit(line.codePointAt(0))))
        marked.append(MARK);
      marked.append(line);
    }
    return marked.toString();
  }

}
