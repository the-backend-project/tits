package com.github.thxmasj.backend.identityprovider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.thxmasj.backend.core.database.DatabaseClient.Config.Authentication;
import java.util.HashMap;
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

  @Test
  public void whenEntraIdThenMssqlDataSourceUsesIt() {
    var config = Config.fromEnvironment(env("DATABASE_AUTHENTICATION", "ActiveDirectoryManagedIdentity", "DATABASE_USER", "client-id")).databaseConfig();
    var dataSource = (com.microsoft.sqlserver.jdbc.SQLServerDataSource) new com.github.thxmasj.backend.database.mssql.MssqlDatabase().dataSource(config);
    assertThat(dataSource.getAuthentication()).isEqualTo("ActiveDirectoryManagedIdentity");
    assertThat(dataSource.getUser()).isEqualTo("client-id");
  }

}
