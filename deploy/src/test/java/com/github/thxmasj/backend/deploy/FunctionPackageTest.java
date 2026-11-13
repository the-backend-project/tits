package com.github.thxmasj.backend.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FunctionPackageTest {

  @TempDir
  Path dir;

  @Test
  void dynamicallyLinkedExecutableRunsWithPackagedLoader() throws IOException {
    Path rootfs = rootfs("demo", "lib64/ld-linux-x86-64.so.2", "lib64/libc.so.6", "lib64/libz.so.1.3");
    FunctionPackage functionPackage = FunctionPackage.build(rootfs, List.of("/demo"), dir.resolve("package.zip"));
    try (FileSystem zip = open(functionPackage.file())) {
      assertThat(Files.readString(zip.getPath(FunctionPackage.LAUNCHER))).endsWith(
          "exec './lib64/ld-linux-x86-64.so.2' --library-path \"$PWD/lib64\" './demo'\n");
      assertThat(Files.readString(zip.getPath("host.json"))).contains("\"enableProxyingHttpRequest\": true");
      assertThat(permissions(zip, "demo")).isEqualTo("rwxr-xr-x");
      assertThat(permissions(zip, "lib64/ld-linux-x86-64.so.2")).isEqualTo("rwxr-xr-x");
    }
  }

  @Test
  void staticallyLinkedExecutableRunsDirectlyWithArguments() throws IOException {
    Path rootfs = rootfs("app/server");
    FunctionPackage functionPackage = FunctionPackage.build(
        rootfs,
        List.of("/app/server", "serve", "it's"),
        dir.resolve("package.zip")
    );
    try (FileSystem zip = open(functionPackage.file())) {
      assertThat(Files.readString(zip.getPath(FunctionPackage.LAUNCHER)))
          .endsWith("exec './app/server' 'serve' 'it'\\''s'\n");
    }
  }

  @Test
  void packageIsReproducible() throws IOException, InterruptedException {
    Path rootfs = rootfs("demo", "lib64/libc.so.6");
    FunctionPackage first = FunctionPackage.build(rootfs, List.of("/demo"), dir.resolve("first.zip"));
    // Zip timestamps have a resolution of a second, so a time leaking into the package shows only across seconds
    Thread.sleep(1100);
    Files.setLastModifiedTime(rootfs.resolve("demo"), FileTime.from(Instant.now().plusSeconds(60)));
    FunctionPackage second = FunctionPackage.build(rootfs, List.of("/demo"), dir.resolve("second.zip"));
    assertThat(second.sha256()).isEqualTo(first.sha256());
    assertThat(Files.readAllBytes(second.file())).isEqualTo(Files.readAllBytes(first.file()));
  }

  @Test
  void entryPointMustBeAbsolute() throws IOException {
    Path rootfs = rootfs("demo");
    assertThatThrownBy(() -> FunctionPackage.build(rootfs, List.of("demo"), dir.resolve("package.zip")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Path rootfs(String... files) throws IOException {
    Path rootfs = dir.resolve("rootfs");
    for (String file : files) {
      Path path = rootfs.resolve(file);
      Files.createDirectories(path.getParent());
      Files.writeString(path, file);
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
    return rootfs;
  }

  private static String permissions(FileSystem zip, String path) throws IOException {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(zip.getPath(path)));
  }

  private static FileSystem open(Path zip) throws IOException {
    return FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("enablePosixFileAttributes", "true"));
  }

}
