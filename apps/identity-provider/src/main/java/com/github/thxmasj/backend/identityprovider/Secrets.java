package com.github.thxmasj.backend.identityprovider;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/// Client secrets are generated with 256 bits of entropy, so a fast hash is enough to store them: a slow hash only
/// protects secrets that can be guessed.
final class Secrets {

  private static final SecureRandom random = new SecureRandom();

  private Secrets() {}

  static String generate() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static String hash(String secret) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /// Compares in constant time, so the time taken does not tell how much of a hash is right
  static boolean matches(String hash, String expectedHash) {
    return hash != null && expectedHash != null && MessageDigest.isEqual(hash.getBytes(UTF_8), expectedHash.getBytes(UTF_8));
  }

}
