package com.github.thxmasj.backend.core.database;

import static java.util.stream.Collectors.joining;

import java.util.List;
import java.util.ServiceLoader;
import java.util.ServiceLoader.Provider;

/// A database the state machine can store its event logs in. Databases are provided by modules of their own, and
/// found on the module path or class path. Besides this, the module of a database provides its storage, i.e. the
/// queries and updates of the state machine, which is given a [Store] of the database to run them on. The databases
/// reached through SQL, which are those of the modules database-mssql, database-pgsql-jdbc, database-pgsql-r2dbc and
/// database-pgsql-vertx, are [SqlDatabase]s.
public interface Database {

  /// The name the database is selected by, e.g. "mssql"
  String name();

  /// The databases found on the module path or class path
  static List<Database> all() {
    return ServiceLoader.load(Database.class).stream().map(Provider::get).toList();
  }

  /// The database with the name
  static Database named(String name) {
    return all().stream()
        .filter(database -> database.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No database named " + name + " among " + names(all())));
  }

  private static String names(List<Database> databases) {
    return databases.stream().map(Database::name).collect(joining(", ", "[", "]"));
  }

}
