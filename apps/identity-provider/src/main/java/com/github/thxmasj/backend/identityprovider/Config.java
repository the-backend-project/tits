package com.github.thxmasj.backend.identityprovider;

import com.github.thxmasj.backend.core.database.DatabaseClient;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/// All configuration is read from environment variables (12-factor III). Only settings with a sensible value in every
/// deploy have defaults; credentials, keys and the location of the database never do.
///
/// @param database        the name of the database, `mssql` or `pgsql`
/// @param issuer          the URL the identity provider is reached at, which is the `iss` of its tokens. Required to
///                        serve.
/// @param signingKeys     a JWK set with the private keys, required to serve. The first key signs the tokens, and the
///                        public keys of all are published, so a new key can be published before it is used.
/// @param tokenLifetime   how long an access token is valid
/// @param audience        the `aud` of the access tokens, if any
/// @param bootstrapClient a client registered at startup, which can register other clients
public record Config(
    int port,
    String database,
    DatabaseClient.Config databaseConfig,
    String schema,
    String role,
    URI issuer,
    String signingKeys,
    Duration tokenLifetime,
    String audience,
    BootstrapClient bootstrapClient
) {

  public record BootstrapClient(String id, String secret) {}

  public static Config fromEnvironment(Map<String, String> env) {
    String database = env.getOrDefault("DATABASE", "mssql");
    return new Config(
        Integer.parseInt(env.getOrDefault("PORT", "8080")),
        database,
        new DatabaseClient.Config(
            required(env, "DATABASE_HOST"),
            Integer.parseInt(env.getOrDefault("DATABASE_PORT", database.equals("pgsql") ? "5432" : "1433")),
            required(env, "DATABASE_NAME"),
            required(env, "DATABASE_USER"),
            required(env, "DATABASE_PASSWORD"),
            Boolean.parseBoolean(env.getOrDefault("DATABASE_TRUST_SERVER_CERTIFICATE", "false")),
            Integer.parseInt(env.getOrDefault("DATABASE_POOL_SIZE", "10")),
            Duration.ofSeconds(Long.parseLong(env.getOrDefault("DATABASE_QUERY_TIMEOUT_SECONDS", "10")))
        ),
        env.getOrDefault("DATABASE_SCHEMA", "identity_provider"),
        env.get("DATABASE_ROLE"),
        env.containsKey("ISSUER") ? issuer(env.get("ISSUER")) : null,
        env.get("SIGNING_KEYS"),
        Duration.ofSeconds(Long.parseLong(env.getOrDefault("TOKEN_LIFETIME_SECONDS", "300"))),
        env.get("TOKEN_AUDIENCE"),
        bootstrapClient(env)
    );
  }

  /// Fails unless the configuration has what serving requires, which migration does not
  public Config requireServing() {
    if (issuer == null)
      throw new IllegalArgumentException("Environment variable ISSUER is required");
    if (signingKeys == null || signingKeys.isBlank())
      throw new IllegalArgumentException("Environment variable SIGNING_KEYS is required");
    return this;
  }

  private static URI issuer(String value) {
    URI issuer = URI.create(value);
    // RFC 8414 section 2
    if (!"https".equals(issuer.getScheme()) && !"http".equals(issuer.getScheme()) || issuer.getHost() == null
        || issuer.getQuery() != null || issuer.getFragment() != null)
      throw new IllegalArgumentException("Environment variable ISSUER must be an http(s) URL without query and fragment");
    return value.endsWith("/") ? URI.create(value.substring(0, value.length() - 1)) : issuer;
  }

  private static BootstrapClient bootstrapClient(Map<String, String> env) {
    String id = env.get("BOOTSTRAP_CLIENT_ID");
    String secret = env.get("BOOTSTRAP_CLIENT_SECRET");
    if (id == null && secret == null)
      return null;
    if (id == null || secret == null)
      throw new IllegalArgumentException("Environment variables BOOTSTRAP_CLIENT_ID and BOOTSTRAP_CLIENT_SECRET must be given together");
    if (!Clients.isValidClientId(id))
      throw new IllegalArgumentException("Environment variable BOOTSTRAP_CLIENT_ID is not a valid client id");
    if (secret.length() < 32)
      throw new IllegalArgumentException("Environment variable BOOTSTRAP_CLIENT_SECRET must have at least 32 characters");
    return new BootstrapClient(id, secret);
  }

  private static String required(Map<String, String> env, String name) {
    String value = env.get(name);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("Environment variable " + name + " is required");
    return value;
  }

}
