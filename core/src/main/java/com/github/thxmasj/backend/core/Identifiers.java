package com.github.thxmasj.backend.core;

import java.util.UUID;

/// Creates the ids of new entities and other rows
///
/// The ids are time-ordered UUIDs (version 7, RFC 9562): they start with the time they are created at, in
/// milliseconds, followed by random bits. New rows are then inserted at the end of the B-tree indexes their ids lead,
/// instead of at random places in them, which keeps the pages of the indexes full and the recently used ones in memory.
/// See [identifiers](https://the-backend-project.github.io/tits/identifiers/).
public final class Identifiers {

  private Identifiers() {}

  /// A new time-ordered id
  public static UUID newId() {
    return UUID.ofEpochMillis(System.currentTimeMillis());
  }

}
