package com.github.thxmasj.backend.core.database.postgres;

/// Identifiers in PostgreSQL. Schema and table names are quoted, so they keep their case. Column names are not quoted,
/// so they are folded to lower case, and are found by the driver in any case.
final class Names {

  private Names() {}

  static String quote(String identifier) {
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  static String table(String schema, String table) {
    return quote(schema) + "." + quote(table);
  }

}
