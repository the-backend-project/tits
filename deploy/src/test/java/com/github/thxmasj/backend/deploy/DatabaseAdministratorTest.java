package com.github.thxmasj.backend.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulumi.azurenative.sql.enums.PrincipalType;
import org.junit.jupiter.api.Test;

public class DatabaseAdministratorTest {

  @Test
  public void whenLoggedInAsUserOrServicePrincipalThenItIsTheAdministrator() {
    assertThat(DatabaseAdministrator.fromAccount("someone@example.com\tuser\n"))
        .isEqualTo(new DatabaseAdministrator("someone@example.com", PrincipalType.User));
    assertThat(DatabaseAdministrator.fromAccount("6f2e0d3a-1b2c-4d5e-8f90-a1b2c3d4e5f6\nservicePrincipal\n"))
        .isEqualTo(new DatabaseAdministrator("6f2e0d3a-1b2c-4d5e-8f90-a1b2c3d4e5f6", PrincipalType.Application));
  }

  @Test
  public void whenLoggedInAsSomethingElseThenFails() {
    assertThatThrownBy(() -> DatabaseAdministrator.fromAccount("vm\nmanagedIdentity\n"))
        .hasMessageContaining("managedIdentity");
    assertThatThrownBy(() -> DatabaseAdministrator.fromAccount(""))
        .hasMessageContaining("az account show");
  }

}
