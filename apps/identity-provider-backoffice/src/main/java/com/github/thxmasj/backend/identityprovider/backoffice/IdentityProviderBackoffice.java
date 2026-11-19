package com.github.thxmasj.backend.identityprovider.backoffice;

import com.github.thxmasj.backend.backoffice.BackofficeApp;
import com.github.thxmasj.backend.identityprovider.Inbox;
import java.time.Duration;

/// The backoffice of the identity provider, an application of its own which reads the entities of the
/// identity provider from its schema, with the identity provider's entity models. See [BackofficeApp].
///
/// Usage:
///
/// - `identity-provider-backoffice` serves the backoffice on `$PORT` until terminated.
/// - `identity-provider-backoffice migrate` creates the backoffice's schema and grants its role read access to the schema of the
///   identity provider, and exits. Run it as a one-off admin process before serving, after the migration of the
///   identity provider.
public final class IdentityProviderBackoffice {

  private IdentityProviderBackoffice() {}

  public static void main(String[] args) {
    // The token lifetime is configuration of the identity provider, and doesn't change its model
    BackofficeApp.run("Identity provider", "identity_provider", Inbox.transitions(Duration.ofMinutes(5)), args, System.getenv());
  }

}
