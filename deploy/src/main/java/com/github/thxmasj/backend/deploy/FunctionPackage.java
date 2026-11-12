package com.github.thxmasj.backend.deploy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// Builds an Azure Functions deployment package from the file system of a container image, running the image's entry
/// point as a [custom handler](https://learn.microsoft.com/azure/azure-functions/functions-custom-handlers) which every
/// HTTP request is proxied to.
///
/// The file system is that of an image built from `scratch` like those of the apps, with an executable linked statically, or
/// dynamically against a C library which is packaged with it. The Functions host provides a C library too, but not
/// necessarily the version the executable was built with, so the packaged dynamic loader is used if there is one.
///
/// The package is reproducible: building it twice from the same file system gives identical bytes.
public record FunctionPackage(Path file, String sha256) {

  static final String LAUNCHER = "launch.sh";
  private static final Pattern DYNAMIC_LOADER = Pattern.compile("ld-(linux|musl)-[^/]*\\.so(\\.[0-9]+)*");
  private static final FileTime TIMESTAMP = FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

  /// @param rootfs the file system of the image
  /// @param command the entry point and arguments of the image, with an absolute path to the executable
  /// @param file where to write the package
  public static FunctionPackage build(Path rootfs, List<String> command, Path file) {
    if (command.isEmpty() || !command.getFirst().startsWith("/"))
      throw new IllegalArgumentException("The image must have an entry point with an absolute path, not " + command);
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.deleteIfExists(file);
      try (FileSystem zip = FileSystems.newFileSystem(
          URI.create("jar:" + file.toAbsolutePath().toUri()),
          Map.of("create", "true", "enablePosixFileAttributes", "true")
      )) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(rootfs)) {
          files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path source : files)
          write(zip, rootfs.relativize(source).toString(), Files.readAllBytes(source),
              Files.getPosixFilePermissions(source));
        write(zip, "host.json", hostJson().getBytes(UTF_8), Set.of());
        write(zip, LAUNCHER, launcher(rootfs, command).getBytes(UTF_8), Set.of());
        try (Stream<Path> directories = Files.walk(zip.getPath("/"))) {
          for (Path directory : directories.filter(p -> p.getNameCount() > 0 && Files.isDirectory(p)).toList())
            setTimes(directory);
        }
      }
      return new FunctionPackage(file, sha256(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(FileSystem zip, String name, byte[] content, Set<PosixFilePermission> permissions)
      throws IOException {
    Path target = zip.getPath(name);
    if (target.getParent() != null)
      Files.createDirectories(target.getParent());
    Files.write(target, content);
    Files.setPosixFilePermissions(target, permissions.isEmpty() ? readable() : permissions);
    setTimes(target);
  }

  /// The zip file system stamps new entries with the current access and creation time too, so all three are fixed.
  private static void setTimes(Path path) throws IOException {
    Files.getFileAttributeView(path, BasicFileAttributeView.class).setTimes(TIMESTAMP, TIMESTAMP, TIMESTAMP);
  }

  private static Set<PosixFilePermission> readable() {
    return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ);
  }

  /// Every request is proxied to the custom handler as is, at the same path, and without requiring a function key.
  static String hostJson() {
    return """
        {
          "version": "2.0",
          "extensions": {
            "http": {
              "routePrefix": ""
            }
          },
          "customHandler": {
            "description": {
              "defaultExecutablePath": "/bin/sh",
              "arguments": ["%s"]
            },
            "http": {
              "enableProxying": true,
              "defaultAuthorizationLevel": "anonymous",
              "routes": [{"route": "{*route}"}]
            }
          }
        }
        """.formatted(LAUNCHER);
  }

  /// The Functions host tells the custom handler which port to listen on in `FUNCTIONS_CUSTOMHANDLER_PORT`, while the
  /// application expects it in `PORT`.
  static String launcher(Path rootfs, List<String> command) throws IOException {
    String executable = "./" + command.getFirst().substring(1);
    String arguments = command.stream().skip(1).map(FunctionPackage::quote).map(a -> " " + a).collect(joining());
    List<Path> loaders;
    try (Stream<Path> walk = Files.walk(rootfs)) {
      loaders = walk.filter(Files::isRegularFile)
          .filter(p -> DYNAMIC_LOADER.matcher(p.getFileName().toString()).matches())
          .sorted()
          .toList();
    }
    String exec;
    if (loaders.isEmpty()) {
      exec = "exec " + quote(executable) + arguments;
    } else {
      String libraryPath;
      try (Stream<Path> walk = Files.walk(rootfs)) {
        libraryPath = walk.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().matches(".*\\.so(\\.[0-9]+)*"))
            .map(p -> rootfs.relativize(p.getParent()).toString())
            .distinct()
            .sorted()
            .map(d -> "$PWD/" + d)
            .collect(joining(":"));
      }
      exec = "exec " + quote("./" + rootfs.relativize(loaders.getFirst())) + " --library-path \"" + libraryPath + "\" "
          + quote(executable) + arguments;
    }
    return """
        # Generated by the deploy module. Starts the application as an Azure Functions custom handler.
        cd "$(dirname "$0")"
        export PORT="$FUNCTIONS_CUSTOMHANDLER_PORT"
        %s
        """.formatted(exec);
  }

  private static String quote(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

}
