package com.github.thxmasj.backend.deploy;

import com.pulumi.azurenative.sql.enums.PrincipalType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/// The Microsoft Entra administrator of the database server, which is the principal the Azure CLI is logged in as. Its
/// object id and tenant are those Pulumi authenticates with, which are the same.
///
/// @param login the name of the administrator, the user principal name of a user or the client id of a service
///     principal
/// @param principalType whether the administrator is a user or a service principal
public record DatabaseAdministrator(String login, PrincipalType principalType) {

  /// The principal the Azure CLI is logged in as, from `az account show`
  public static DatabaseAdministrator azureCli() {
    String account;
    try {
      Process process = new ProcessBuilder("az", "account", "show", "--query", "[user.name, user.type]", "--output", "tsv")
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start();
      account = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0)
        throw new IllegalStateException("az account show failed. Log in with az login.");
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to run the Azure CLI", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
    return fromAccount(account);
  }

  /// @param account the user name and type of the account, separated by tabs or line breaks
  static DatabaseAdministrator fromAccount(String account) {
    String[] fields = account.strip().split("\\s+");
    if (fields.length != 2 || fields[0].isEmpty())
      throw new IllegalStateException("az account show has no user name and type: " + account);
    String name = fields[0];
    String type = fields[1];
    return switch (type) {
      case "user" -> new DatabaseAdministrator(name, PrincipalType.User);
      case "servicePrincipal" -> new DatabaseAdministrator(name, PrincipalType.Application);
      default -> throw new IllegalStateException("The Azure CLI is logged in as a " + type
          + ", which can't be the administrator of the database server");
    };
  }

}
