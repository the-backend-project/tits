package com.github.thxmasj.backend.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ContainerImageTest {

  @Test
  public void whenOnlyEntrypointThenItIsTheCommand() {
    assertThat(ContainerImage.parse("app", "sha256:abc\nlinux/amd64\n/hello-world\n\n"))
        .isEqualTo(new ContainerImage("sha256:abc", "linux/amd64", List.of("/hello-world")));
  }

  @Test
  public void whenEntrypointAndCmdThenTheCommandIsBoth() {
    assertThat(ContainerImage.parse("app", "sha256:abc\nlinux/amd64\n/app\nserve\n--port\n8080\n\n").command())
        .containsExactly("/app", "serve", "--port", "8080");
  }

  @Test
  public void whenOtherPlatformThenFails() {
    assertThatThrownBy(() -> ContainerImage.parse("app", "sha256:abc\nlinux/arm64\n/app\n\n"))
        .hasMessageContaining("linux/arm64");
  }

  @Test
  public void whenExtractedDirectoryIsReadOnlyThenItIsDeletedAnyway(@TempDir Path temp) throws Exception {
    Path rootfs = temp.resolve("rootfs");
    Path lib64 = Files.createDirectories(rootfs.resolve("lib64"));
    Files.writeString(lib64.resolve("libm.so.6"), "x");
    Files.createSymbolicLink(rootfs.resolve("lib"), Path.of("lib64"));
    Files.setPosixFilePermissions(lib64, PosixFilePermissions.fromString("r-xr-xr-x"));
    ContainerImage.deleteRecursively(rootfs);
    assertThat(rootfs).doesNotExist();
  }

}
