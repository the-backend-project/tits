package com.github.thxmasj.backend.identityprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.database.DatabaseClient.Config.Authentication;
import com.github.thxmasj.backend.identityprovider.ImportedClients.ImportedClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ConfigTest {

  private static Map<String, String> env(String... keysAndValues) {
    var env = new HashMap<>(Map.of("DATABASE_HOST", "example.database.windows.net", "DATABASE_NAME", "idp"));
    for (int i = 0; i < keysAndValues.length; i += 2)
      env.put(keysAndValues[i], keysAndValues[i + 1]);
    return env;
  }

  @Test
  public void whenNoAuthenticationThenSqlPasswordWithUserAndPassword() {
    var config = Config.fromEnvironment(env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p")).databaseConfig();
    assertThat(config.authentication()).isEqualTo(Authentication.SqlPassword);
    assertThat(config.user()).isEqualTo("u");
    assertThat(config.password()).isEqualTo("p");
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_USER", "u")))
        .hasMessageContaining("DATABASE_PASSWORD");
  }

  @Test
  public void whenServicePrincipalThenClientIdAndSecretAreRequired() {
    var config = Config.fromEnvironment(env(
        "DATABASE_AUTHENTICATION", "ActiveDirectoryServicePrincipal", "DATABASE_USER", "client-id", "DATABASE_PASSWORD", "client-secret"
    )).databaseConfig();
    assertThat(config.authentication()).isEqualTo(Authentication.ActiveDirectoryServicePrincipal);
    assertThat(config.user()).isEqualTo("client-id");
    assertThat(config.password()).isEqualTo("client-secret");
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryServicePrincipal", "DATABASE_USER", "client-id")))
        .hasMessageContaining("DATABASE_PASSWORD");
  }

  @Test
  public void whenManagedIdentityThenUserIsOptionalAndPasswordIsNotAllowed() {
    var systemAssigned = Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity")).databaseConfig();
    assertThat(systemAssigned.authentication()).isEqualTo(Authentication.ActiveDirectoryManagedIdentity);
    assertThat(systemAssigned.user()).isNull();
    assertThat(systemAssigned.password()).isNull();
    var userAssigned = Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity", "DATABASE_USER", "client-id")).databaseConfig();
    assertThat(userAssigned.user()).isEqualTo("client-id");
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity", "DATABASE_PASSWORD", "p")))
        .hasMessageContaining("DATABASE_PASSWORD");
  }

  @Test
  public void whenAuthenticationIsUnknownOrEntraIdOnPgsqlThenFails() {
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "Kerberos", "DATABASE_USER", "u", "DATABASE_PASSWORD", "p")))
        .hasMessageContaining("DATABASE_AUTHENTICATION");
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE", "pgsql", "DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity")))
        .hasMessageContaining("pgsql");
  }

  private static final String hash = "$2a$10$Uekj8xXissq6Wy3fUSUOW.epdfkVAf3.uY6/DkL8L43gfnvmTrB82";

  private static Map<String, String> clients(String json) {
    return env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p", "CLIENTS", json);
  }

  @Test
  public void whenClientsAreGivenInlineOrInFileThenTheyAreImported() throws Exception {
    String json = """
        [
          {"client_id": "a", "secret_hash": "%s", "subject": "https://wallet.example.com/provider/example", "scope": "x y", "client_name": "A"},
          {"client_id": "b", "secret_hash": "%s", "scope": ""},
          {"client_id": "c", "secret_hash": "%s", "subject": null}
        ]
        """.formatted(hash, hash.replace("$2a$", "$2b$"), hash.replace("$2a$", "$2y$"));
    var inline = Config.fromEnvironment(clients(json)).clients();
    assertThat(inline).containsExactly(
        new ImportedClient("a", hash, "https://wallet.example.com/provider/example", "x y", "A"),
        new ImportedClient("b", hash.replace("$2a$", "$2b$"), null, "", null),
        new ImportedClient("c", hash.replace("$2a$", "$2y$"), null, "", null)
    );
    Path file = Files.createTempFile("clients", ".json");
    try {
      Files.writeString(file, json);
      assertThat(Config.fromEnvironment(env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p", "CLIENTS_FILE", file.toString())).clients())
          .isEqualTo(inline);
    } finally {
      Files.delete(file);
    }
    assertThat(Config.fromEnvironment(env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p")).clients()).isEmpty();
  }

  @Test
  public void whenClientsAreInvalidThenFails() {
    for (String json : List.of(
        "not json",
        "{}",
        "[42]",
        "[{\"secret_hash\": \"%s\"}]".formatted(hash),
        "[{\"client_id\": \"not valid\", \"secret_hash\": \"%s\"}]".formatted(hash),
        "[{\"client_id\": \"a\"}]",
        "[{\"client_id\": \"a\", \"secret_hash\": \"plain-secret\"}]",
        "[{\"client_id\": \"a\", \"secret_hash\": \"{bcrypt}%s\"}]".formatted(hash),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\"}]".formatted(hash.replace("$2a$", "$2x$")),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\", \"subject\": \" \"}]".formatted(hash),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\", \"scope\": \"a  b\"}]".formatted(hash),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\", \"scope\": [\"a\"]}]".formatted(hash),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\", \"secret\": \"s\"}]".formatted(hash),
        "[{\"client_id\": \"a\", \"secret_hash\": \"%s\"}, {\"client_id\": \"a\", \"secret_hash\": \"%s\"}]".formatted(hash, hash)
    ))
      assertThatThrownBy(() -> Config.fromEnvironment(clients(json))).as(json).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void whenClientsAreAmbiguousOrMissingThenFails() {
    String json = "[{\"client_id\": \"admin\", \"secret_hash\": \"%s\"}]".formatted(hash);
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p", "CLIENTS", json, "CLIENTS_FILE", "/clients.json")))
        .hasMessageContaining("CLIENTS_FILE and CLIENTS");
    assertThatThrownBy(() -> Config.fromEnvironment(env("DATABASE_USER", "u", "DATABASE_PASSWORD", "p", "CLIENTS_FILE", "/does/not/exist.json")))
        .hasMessageContaining("CLIENTS_FILE");
    assertThatThrownBy(() -> Config.fromEnvironment(env(
        "DATABASE_USER", "u", "DATABASE_PASSWORD", "p", "CLIENTS", json, "BOOTSTRAP_CLIENT_ID", "admin", "BOOTSTRAP_CLIENT_SECRET", "A_bootstrap_secret_of_32_characters"
    ))).hasMessageContaining("bootstrap");
  }

  @Test
  public void whenEntraIdThenMssqlDataSourceUsesIt() {
    var config = Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity", "DATABASE_USER", "client-id")).databaseConfig();
    var dataSource = (com.microsoft.sqlserver.jdbc.SQLServerDataSource) new com.github.thxmasj.backend.database.mssql.MssqlDatabase().dataSource(config);
    assertThat(dataSource.getAuthentication()).isEqualTo("ActiveDirectoryManagedIdentity");
    assertThat(dataSource.getUser()).isEqualTo("client-id");
  }

}
