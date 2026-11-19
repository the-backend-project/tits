package com.github.thxmasj.backend.core;

import java.util.UUID;

/// Creates the ids of new entities and other rows
///
/// The ids are time-ordered UUIDs (version 7, RFC 9562): they start with the time they are created at, in
/// milliseconds, followed by random bits. New rows are then inserted at the end of the B-tree indexes their ids lead,
/// instead of at random places in them, which keeps the pages of the indexes full and the recently used ones in memory.
/// See [identifiers](https://the-backend-project.github.io/tits/identifiers/).
final class Identifiers {

  private Identifiers() {}

  /// A new time-ordered id
  ///
  /// It has the layout of `UUID.ofEpochMillis`, which is not used as it was added in Java 26, and the libraries target
  /// Java 25. The random bits are those of a random UUID (version 4), which has random bits
  /// where version 7 has, and the same variant: the 48 bits of time replace its first 48 random bits, and the version
  /// is set to 7.
  public static UUID newId() {
    return newId(System.currentTimeMillis());
  }

  /// A new time-ordered id with a time in milliseconds since the Unix epoch
  static UUID newId(long epochMillis) {
    UUID random = UUID.randomUUID();
    long mostSigBits = (epochMillis << 16)
        | 0x7000L
        | (random.getMostSignificantBits() & 0x0FFFL);
    return new UUID(mostSigBits, random.getLeastSignificantBits());
  }

}
