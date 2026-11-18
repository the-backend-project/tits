package com.github.thxmasj.backend.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class DebugTest {

  @Test
  public void aTextOfOneLineIsWrittenAsItIs() {
    assertEquals("onEvent SYS-1234 Parcel sent", Debug.marked("onEvent SYS-1234 Parcel sent"));
  }

  @Test
  public void everyLineAfterTheFirstIsMarked() {
    assertEquals(
        "Changes to store:\n  | Parcel\n  | Complaint\n  | Courier",
        Debug.marked("Changes to store:\nParcel\r\nComplaint\rCourier")
    );
  }

  @Test
  public void dataCanNotMakeUpALineOfItsOwn() {
    // A request made to pass for an event, as the debug output of the transition it triggers has it
    String forged = "{\"severity\":\"CRITICAL\",\"eventType\":\"Money lost\"}";
    String text = Debug.marked(
        "calculate [Request received] (input: POST / HTTP/1.1\nContent-Length:51\n\n" + forged + "\nX)"
    );

    assertTrue(text.contains(forged));
    assertTrue(text.lines().noneMatch(line -> line.stripLeading().startsWith("{")));
  }

  @Test
  public void aFirstLineIsMarkedUnlessItBeginsWithALetterOrADigit() {
    assertEquals("2026-11-14T12:00Z: Forwarding GET /", Debug.marked("2026-11-14T12:00Z: Forwarding GET /"));
    assertEquals("  | {\"severity\":\"ERROR\"}", Debug.marked("{\"severity\":\"ERROR\"}"));
    assertEquals("  |  {}\n  | {}", Debug.marked(" {}\n{}"));
    // A byte order mark, which a reader may skip
    assertEquals("  | \uFEFF{}", Debug.marked("\uFEFF{}"));
    assertEquals("\n  | {}", Debug.marked("\n{}"));
  }

  /// Console output of the library which did not go through [Debug] would not be marked. Only
  /// [com.github.thxmasj.backend.core.JsonLinesListener] writes lines of its own.
  @Test
  public void theLibraryWritesToTheConsoleThroughDebugOnly() throws IOException {
    Path sources = Path.of("src/main/java");
    assumeTrue(Files.isDirectory(sources), "Not run from the directory of the module");
    try (Stream<Path> files = Files.walk(sources)) {
      List<String> writes = files
          .filter(file -> file.toString().endsWith(".java"))
          .filter(file -> !Set.of("Debug.java", "JsonLinesListener.java").contains(file.getFileName().toString()))
          .flatMap(DebugTest::consoleWrites)
          .toList();
      assertEquals(List.of(), writes);
    }
  }

  /// The lines of the file which write to the console, with the file and line number
  private static Stream<String> consoleWrites(Path file) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return IntStream.range(0, lines.size())
        .filter(i -> !lines.get(i).strip().startsWith("//"))
        .filter(i -> Stream.of("System.out", "System.err", "printStackTrace(").anyMatch(lines.get(i)::contains))
        .mapToObj(i -> file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
  }

}
