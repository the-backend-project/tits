package com.github.thxmasj.backend.database.mssql;

import com.microsoft.sqlserver.jdbc.SQLServerAccessTokenCallback;
import com.microsoft.sqlserver.jdbc.SqlAuthenticationToken;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// Access tokens of the managed identity of the host, for logging in to Azure SQL with Microsoft Entra ID.
///
/// The driver fetches these itself with azure-identity, which does not work in a native image, so they are fetched
/// here instead: from the endpoint which Azure App Service and Azure Functions give in `IDENTITY_ENDPOINT` and
/// `IDENTITY_HEADER`, or else from the Instance Metadata Service of virtual machines and containers. A token is reused
/// until shortly before it expires.
///
/// @see <a href="https://learn.microsoft.com/azure/app-service/overview-managed-identity#rest-endpoint-reference">App Service</a>
/// @see <a href="https://learn.microsoft.com/entra/identity/managed-identities-azure-resources/how-to-use-vm-token#get-a-token-using-http">Instance Metadata Service</a>
final class ManagedIdentityTokens implements SQLServerAccessTokenCallback {

  static final URI INSTANCE_METADATA_SERVICE = URI.create("http://169.254.169.254/metadata/identity/oauth2/token");
  private static final Duration TIMEOUT = Duration.ofSeconds(10);
  /// How long before it expires a token is renewed
  private static final Duration RENEWAL = Duration.ofMinutes(5);

  private final String clientId;
  private final URI endpoint;
  private final String identityHeader;
  private final HttpClient httpClient;
  private final Clock clock;
  private SqlAuthenticationToken token;

  /// @param clientId the client id of a user-assigned managed identity, or null for the system-assigned one
  /// @param env the environment, where `IDENTITY_ENDPOINT` and `IDENTITY_HEADER` are the endpoint of App Service
  ManagedIdentityTokens(String clientId, Map<String, String> env, Clock clock) {
    this.clientId = clientId;
    String endpoint = env.get("IDENTITY_ENDPOINT");
    String header = env.get("IDENTITY_HEADER");
    if (endpoint != null && !endpoint.isBlank() && header != null && !header.isBlank()) {
      this.endpoint = URI.create(endpoint);
      this.identityHeader = header;
    } else {
      this.endpoint = INSTANCE_METADATA_SERVICE;
      this.identityHeader = null;
    }
    this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    this.clock = clock;
  }

  ManagedIdentityTokens(String clientId) {
    this(clientId, System.getenv(), Clock.systemUTC());
  }

  /// @param resource the resource the token is for, `https://database.windows.net/` for Azure SQL
  /// @param authority the authority, which is that of the managed identity rather than the one given
  @Override
  public synchronized SqlAuthenticationToken getAccessToken(String resource, String authority) {
    if (token == null || !token.getExpiresOn().toInstant().isAfter(clock.instant().plus(RENEWAL)))
      token = fetch(resource);
    return token;
  }

  private SqlAuthenticationToken fetch(String resource) {
    HttpRequest request = request(resource);
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to get a managed identity token from " + endpoint, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted getting a managed identity token", e);
    }
    if (response.statusCode() != 200)
      throw new IllegalStateException("Failed to get a managed identity token from " + endpoint + ": "
          + response.statusCode() + " " + response.body());
    JsonNode body = JsonMapper.shared().readTree(response.body());
    String accessToken = body.path("access_token").asString(null);
    String expiresOn = body.path("expires_on").asString(null);
    if (accessToken == null || expiresOn == null)
      throw new IllegalStateException("The managed identity token from " + endpoint + " has no access_token or expires_on");
    return new SqlAuthenticationToken(accessToken, Date.from(Instant.ofEpochSecond(Long.parseLong(expiresOn))));
  }

  HttpRequest request(String resource) {
    StringBuilder query = new StringBuilder()
        .append("api-version=").append(identityHeader != null ? "2019-08-01" : "2018-02-01")
        .append("&resource=").append(URLEncoder.encode(resource, StandardCharsets.UTF_8));
    if (clientId != null)
      query.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query)).timeout(TIMEOUT).GET();
    if (identityHeader != null)
      request.header("X-IDENTITY-HEADER", identityHeader);
    else
      request.header("Metadata", "true");
    return request.build();
  }

}
