package com.github.thxmasj.backend.helloworld;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.util.Map;

/// All configuration is read from environment variables (12-factor III). Only settings with a sensible value in every
/// deploy have defaults; credentials and the location of the database never do.
public record Config(
    int port,
    DatabaseClient.Config database,
    String schema,
    String role
) {

  public static Config fromEnvironment(Map<String, String> env) {
    return new Config(
        Integer.parseInt(env.getOrDefault("PORT", "8080")),
        DatabaseClient.Config.fromEnvironment(env, "mssql"),
        env.getOrDefault("DATABASE_SCHEMA", "hello_world"),
        env.get("DATABASE_ROLE")
    );
  }

}
