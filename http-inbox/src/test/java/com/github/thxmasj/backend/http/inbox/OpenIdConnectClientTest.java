package com.github.thxmasj.backend.http.inbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class OpenIdConnectClientTest {

  // Not reached, as an invalid secret fails before the provider is discovered
  private final OpenIdConnectClient client = new OpenIdConnectClient(new OpenIdConnectClient.Config(
      URI.create("http://127.0.0.1:1"), "app", null, "openid", URI.create("https://app.example.com/callback")
  ));

  @Test
  public void whenSecretIsNoValidPkceVerifierThenSignInFails() {
    for (String secret : new String[] {null, "too-short", "c0ffee00-1234-4abc-8def-0123456789ab", Secrets.generate() + "/"}) {
      assertThatThrownBy(() -> client.authorizationUrl("state", secret).block())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("43 to 128 characters");
      assertThatThrownBy(() -> client.tokenRequest("code", secret).block())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

}
