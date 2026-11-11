package com.github.thxmasj.backend.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

public class AccessTokenSupplierTest {

  @Test
  public void schedulerThreadDoesNotKeepJvmAliveAndStopsOnClose() throws Exception {
    var fetchThread = new CompletableFuture<Thread>();
    HttpClient unreachable = _ -> {
      fetchThread.complete(Thread.currentThread());
      return Mono.error(new ConnectException("Connection refused"));
    };
    Thread thread;
    try (var _ = new AccessTokenSupplier.Builder(unreachable, URI.create("http://localhost/token"))
        .clientCredentials("id", "secret")
        .build()) {
      thread = fetchThread.get(5, TimeUnit.SECONDS);
      assertThat(thread.isDaemon()).isTrue();
      assertThat(thread.getName()).isEqualTo("access-token-supplier");
    }
    assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
  }

}
