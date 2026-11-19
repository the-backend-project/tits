package com.github.thxmasj.backend.backoffice;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/// The secrets of sign-ins and the tokens of sessions, of 256 bits of entropy, so a fast hash is enough to store them
final class Secrets {

  private static final SecureRandom random = new SecureRandom();

  private Secrets() {}

  /// A new secret of 256 bits, base64url encoded, which is 43 characters as a PKCE verifier must have at least
  static String generate() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /// The SHA-256 hash of a secret, base64url encoded
  static String hash(String secret) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

}
