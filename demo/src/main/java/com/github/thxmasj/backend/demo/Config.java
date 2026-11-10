package com.github.thxmasj.backend.demo;

import com.github.thxmasj.backend.core.database.Client;
import java.time.Duration;
import java.util.Map;

/// All configuration is read from environment variables (12-factor III). Only settings with a sensible value in every
/// deploy have defaults; credentials and the location of the database never do.
public record Config(
    int port,
    Client.Config database,
    String schema,
    String role
) {

  public static Config fromEnvironment(Map<String, String> env) {
    return new Config(
        Integer.parseInt(env.getOrDefault("PORT", "8080")),
        new Client.Config(
            required(env, "DATABASE_HOST"),
            Integer.parseInt(env.getOrDefault("DATABASE_PORT", "1433")),
            required(env, "DATABASE_NAME"),
            required(env, "DATABASE_USER"),
            required(env, "DATABASE_PASSWORD"),
            Boolean.parseBoolean(env.getOrDefault("DATABASE_TRUST_SERVER_CERTIFICATE", "false")),
            Integer.parseInt(env.getOrDefault("DATABASE_POOL_SIZE", "10")),
            Duration.ofSeconds(Long.parseLong(env.getOrDefault("DATABASE_QUERY_TIMEOUT_SECONDS", "10")))
        ),
        env.getOrDefault("DATABASE_SCHEMA", "demo"),
        env.get("DATABASE_ROLE")
    );
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Environment variable " + name + " is required");
    return value;
  }

}
