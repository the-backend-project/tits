package com.github.thxmasj.backend.database.mssql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.sqlserver.jdbc.SqlAuthenticationToken;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ManagedIdentityTokensTest {

  private static final String RESOURCE = "https://database.windows.net/";
  private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile long expiresOn = NOW.plus(Duration.ofHours(1)).getEpochSecond();

  @BeforeEach
  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/msi/token", exchange -> {
      requests.add(exchange.getRequestURI().getQuery() + " " + exchange.getRequestHeaders().getFirst("X-IDENTITY-HEADER"));
      byte[] body = ("{\"access_token\":\"token-" + requests.size() + "\",\"expires_on\":\"" + expiresOn
          + "\",\"resource\":\"" + RESOURCE + "\",\"token_type\":\"Bearer\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  public void stop() {
    server.stop(0);
  }

  private ManagedIdentityTokens tokens(String clientId) {
    return new ManagedIdentityTokens(
        clientId,
        Map.of("IDENTITY_ENDPOINT", "http://127.0.0.1:" + server.getAddress().getPort() + "/msi/token", "IDENTITY_HEADER", "secret"),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );
  }

  @Test
  public void whenAppServiceEndpointThenTokenIsFetchedFromItWithTheHeader() {
    SqlAuthenticationToken token = tokens("client-id").getAccessToken(RESOURCE, "https://login.microsoftonline.com/tenant");
    assertEquals("token-1", token.getAccessToken());
    assertEquals(Instant.ofEpochSecond(expiresOn), token.getExpiresOn().toInstant());
    assertEquals(List.of("api-version=2019-08-01&resource=https://database.windows.net/&client_id=client-id secret"), requests);
  }

  @Test
  public void whenTokenIsValidThenItIsReusedUntilShortlyBeforeItExpires() {
    var tokens = tokens(null);
    SqlAuthenticationToken first = tokens.getAccessToken(RESOURCE, null);
    assertSame(first, tokens.getAccessToken(RESOURCE, null));
    assertEquals(1, requests.size());

    expiresOn = NOW.plus(Duration.ofMinutes(4)).getEpochSecond();
    tokens = tokens(null);
    tokens.getAccessToken(RESOURCE, null);
    tokens.getAccessToken(RESOURCE, null);
    assertEquals(3, requests.size());
  }

  @Test
  public void whenEndpointFailsThenGettingTheTokenFails() {
    status = 500;
    assertThrows(IllegalStateException.class, () -> tokens(null).getAccessToken(RESOURCE, null));
  }

  @Test
  public void whenNoAppServiceEndpointThenInstanceMetadataServiceIsUsed() {
    HttpRequest request = new ManagedIdentityTokens(null, Map.of(), Clock.systemUTC()).request(RESOURCE);
    assertEquals(
        ManagedIdentityTokens.INSTANCE_METADATA_SERVICE + "?api-version=2018-02-01&resource=https%3A%2F%2Fdatabase.windows.net%2F",
        request.uri().toString()
    );
    assertEquals("true", request.headers().firstValue("Metadata").orElseThrow());
    assertNull(request.headers().firstValue("X-IDENTITY-HEADER").orElse(null));
  }

}
