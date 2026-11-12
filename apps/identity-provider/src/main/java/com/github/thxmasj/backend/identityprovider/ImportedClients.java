package com.github.thxmasj.backend.identityprovider;

import com.github.thxmasj.backend.core.DataType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.crypto.bcrypt.BCrypt;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/// Clients declared in the configuration with the BCrypt hashes of their secrets, e.g. those of Spring Security's
/// BCryptPasswordEncoder, so the clients of another identity provider keep their secrets when moved here.
///
/// The BCrypt hashes are kept by the [Edge] and never enter the event log. An imported client is registered with the
/// SHA-256 hash of its BCrypt hash as its secret hash, which the edge passes on for a secret that matches the BCrypt
/// hash. A secret which does not match gets a secret hash which can't match any, so presenting the BCrypt hash itself
/// as the secret does not authenticate.
final class ImportedClients {

  /// The client id of the registrar of imported clients, which no client can have
  static final String Registrar = "(import)";

  /// The secret hash passed on for an imported client whose secret does not match, which no hash can be equal to
  static final String Mismatch = "(mismatch)";

  private static final Pattern bcryptHash = Pattern.compile("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");
  private static final Set<String> fields = Set.of("client_id", "secret_hash", "subject", "scope", "client_name");

  private ImportedClients() {}

  /// @param secretHash the BCrypt hash of the client's secret
  /// @param subject    the `sub` of the client's tokens, or null for its client id
  /// @param scope      the space separated scopes of the client, possibly empty
  /// @param name       a name for people, which is only logged
  record ImportedClient(String clientId, String secretHash, String subject, String scope, String name) {}

  /// The clients of the environment variable CLIENTS_FILE, a path to a JSON array of clients, or of CLIENTS, the JSON
  /// array itself
  static List<ImportedClient> fromEnvironment(Map<String, String> env) {
    String file = blankToNull(env.get("CLIENTS_FILE"));
    String inline = blankToNull(env.get("CLIENTS"));
    if (file != null && inline != null)
      throw new IllegalArgumentException("Environment variables CLIENTS_FILE and CLIENTS can't both be set");
    if (file != null) {
      try {
        return parse(Files.readString(Path.of(file)), "CLIENTS_FILE " + file);
      } catch (IOException e) {
        throw new IllegalArgumentException("Failed to read CLIENTS_FILE " + file + ": " + e.getMessage(), e);
      }
    }
    if (inline != null)
      return parse(inline, "CLIENTS");
    return List.of();
  }

  /// The clients of a JSON array, e.g.
  /// `[{"client_id": "orders", "secret_hash": "$2a$10$...", "subject": "urn:orders", "scope": "orders:read"}]`
  static List<ImportedClient> parse(String json, String source) {
    JsonNode array;
    try {
      array = DataType.objectMapper.readTree(json);
    } catch (JacksonException e) {
      throw new IllegalArgumentException(source + " is not JSON: " + e.getOriginalMessage());
    }
    if (array == null || !array.isArray())
      throw new IllegalArgumentException(source + " is not a JSON array");
    var clients = new ArrayList<ImportedClient>();
    var clientIds = new HashSet<String>();
    for (int i = 0; i < array.size(); i++) {
      ImportedClient client = client(array.get(i), source + " [" + i + "]");
      if (!clientIds.add(client.clientId()))
        throw new IllegalArgumentException(source + " [" + i + "]: client_id " + client.clientId() + " is duplicated");
      clients.add(client);
    }
    return List.copyOf(clients);
  }

  private static ImportedClient client(JsonNode node, String source) {
    if (node == null || !node.isObject())
      throw new IllegalArgumentException(source + " is not a JSON object");
    Set<String> unknown = node.propertyNames().stream().filter(name -> !fields.contains(name)).collect(Collectors.toSet());
    if (!unknown.isEmpty())
      throw new IllegalArgumentException(source + " has unknown fields " + unknown + ", only " + fields + " are allowed");
    String clientId = string(node, "client_id", source);
    if (!Clients.isValidClientId(clientId))
      throw new IllegalArgumentException(source + ": client_id must be 1 to 100 of the characters A-Z, a-z, 0-9, '.', '_', '~' and '-'");
    String secretHash = string(node, "secret_hash", source);
    if (secretHash == null || !bcryptHash.matcher(secretHash).matches())
      throw new IllegalArgumentException(source + ": secret_hash of " + clientId + " must be a BCrypt hash ($2a$, $2b$ or $2y$)");
    String subject = string(node, "subject", source);
    if (subject != null && !Clients.isValidSubject(subject))
      throw new IllegalArgumentException(source + ": subject of " + clientId + " must be 1 to " + Clients.maxSubjectLength + " characters, not blank and without control characters");
    String scope = string(node, "scope", source);
    if (scope != null && !scope.isEmpty() && !Scopes.isValid(scope))
      throw new IllegalArgumentException(source + ": scope of " + clientId + " must be a space separated list of scopes");
    return new ImportedClient(clientId, secretHash, subject, scope != null ? scope : "", string(node, "client_name", source));
  }

  /// The string value of a field, or null if it is absent or null
  private static String string(JsonNode node, String field, String source) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull())
      return null;
    if (!value.isString())
      throw new IllegalArgumentException(source + ": " + field + " must be a string");
    return value.asString();
  }

  /// The hash of the secret an imported client is registered with
  static String secretHash(ImportedClient client) {
    return Secrets.hash(client.secretHash());
  }

  /// The secret hash to pass on for a secret presented for an imported client with the BCrypt hash
  static String secretHash(String secret, String bcryptHash) {
    return BCrypt.checkpw(secret, bcryptHash) ? Secrets.hash(bcryptHash) : Mismatch;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

}
