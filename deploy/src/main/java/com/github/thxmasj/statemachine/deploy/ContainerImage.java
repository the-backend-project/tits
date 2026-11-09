package com.github.thxmasj.statemachine.deploy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// A container image in the local Docker engine. The application is distributed as an image, which is run as is for
/// the migrate admin process, and whose file system is repackaged for Azure Functions.
public record ContainerImage(String id, String platform, List<String> command) {

  /// The platform Azure Functions runs on
  public static final String PLATFORM = "linux/amd64";

  public static ContainerImage inspect(String reference) {
    String format = "{{.Id}}\n{{.Os}}/{{.Architecture}}\n"
        + "{{range .Config.Entrypoint}}{{println .}}{{end}}{{range .Config.Cmd}}{{println .}}{{end}}";
    List<String> lines = docker("image", "inspect", "--format", format, reference).lines().toList();
    ContainerImage image = new ContainerImage(lines.get(0), lines.get(1), lines.subList(2, lines.size()));
    if (!image.platform().equals(PLATFORM))
      throw new IllegalArgumentException("Image " + reference + " is for " + image.platform() + ", not " + PLATFORM
          + ". Build it with --platform " + PLATFORM + ".");
    return image;
  }

  /// Extracts the file system of the image into an empty directory
  public void export(Path directory) {
    try {
      deleteRecursively(directory);
      Files.createDirectories(directory);
      Path tar = directory.resolveSibling(directory.getFileName() + ".tar");
      String container = docker("create", "--platform", PLATFORM, id).strip();
      try {
        docker("export", "--output", tar.toString(), container);
      } finally {
        docker("rm", container);
      }
      run(List.of("tar", "-x", "-f", tar.toString(), "-C", directory.toString()));
      Files.delete(tar);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path directory) throws IOException {
    if (!Files.exists(directory))
      return;
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList())
        Files.delete(path);
    }
  }

  private static String docker(String... arguments) {
    List<String> command = new ArrayList<>(List.of("docker"));
    command.addAll(List.of(arguments));
    return run(command);
  }

  private static String run(List<String> command) {
    try {
      Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0)
        throw new IllegalStateException(String.join(" ", command) + " failed with exit code " + process.exitValue());
      return output;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to run " + String.join(" ", command), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

}
