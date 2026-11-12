package com.github.thxmasj.backend.database.pgsql;

import static com.github.thxmasj.backend.database.pgsql.Names.quote;
import static com.github.thxmasj.backend.database.pgsql.Names.table;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/// The same tables as for SQL Server, with PostgreSQL types. Index names are prefixed with their table, as they are
/// unique within the schema and not only within the table.
public class CreateSchema {

  private final String schema;
  private final String role;

  public CreateSchema(String schemaName, String role) {
    this.schema = schemaName;
    this.role = role;
  }

  public String sql() {
    return createSql();
  }

  public long checksum() {
    var crc32 = new CRC32();
    crc32.update(coreSql().getBytes(StandardCharsets.UTF_8));
    return crc32.getValue();
  }

  private String createSql() {
    return
        """
        CREATE SCHEMA IF NOT EXISTS {schema};
        GRANT USAGE ON SCHEMA {schema} TO {role};
        CREATE TABLE {Metadata} (Checksum BIGINT NOT NULL);
        INSERT INTO {Metadata} (Checksum) VALUES ({checksum});
        """.replace("{schema}", quote(schema))
            .replace("{role}", quote(role))
            .replace("{Metadata}", table(schema, "Metadata"))
            .replace("{checksum}", String.valueOf(checksum()))
        + coreSql();
  }

  /// The tables, as verified by the checksum
  private String coreSql() {
    return
        """
        CREATE TABLE {schema}."Entity"
        (
            EntityId      UUID NOT NULL,
            EntityModelId UUID NOT NULL,
            CONSTRAINT "pkEntity" PRIMARY KEY (EntityId)
        );
        GRANT INSERT, SELECT ON {schema}."Entity" TO {role};

        CREATE TABLE {schema}."Event"
        (
            EntityId    UUID      NOT NULL,
            EventNumber SMALLINT  NOT NULL,
            Type        UUID      NOT NULL,
            Data        BYTEA,
            Timestamp   TIMESTAMP NOT NULL,
            CONSTRAINT "pkEvent" PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON {schema}."Event" TO {role};

        CREATE TABLE {schema}."IndexEvent"
        (
            EntityId    UUID      NOT NULL,
            EventNumber SMALLINT  NOT NULL,
            Type        UUID      NOT NULL,
            Data        BYTEA     NOT NULL,
            Timestamp   TIMESTAMP NOT NULL,
            CONSTRAINT "pkIndexEvent" PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON {schema}."IndexEvent" TO {role};
        CREATE INDEX "ixIndexEvent_Type_Data" ON {schema}."IndexEvent" (Type, Data);

        CREATE TABLE {schema}."IdEvent"
        (
            EntityId    UUID      NOT NULL,
            EventNumber SMALLINT  NOT NULL,
            Type        UUID      NOT NULL,
            Data        BYTEA     NOT NULL,
            Timestamp   TIMESTAMP NOT NULL,
            CONSTRAINT "pkIdEvent" PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON {schema}."IdEvent" TO {role};
        CREATE UNIQUE INDEX "ixIdEvent_Type_Data" ON {schema}."IdEvent" (Type, Data);

        CREATE TABLE {schema}."DelayedTrigger"
        (
            EntityId      UUID         NOT NULL,
            EntityModelId UUID         NOT NULL,
            EventNumber   SMALLINT     NOT NULL,
            Type          UUID         NOT NULL,
            Data          BYTEA,
            Deadline      TIMESTAMP(2) NOT NULL,
            CorrelationId VARCHAR(36)  NOT NULL,
            Attempt       INT          NOT NULL
        );
        GRANT INSERT, DELETE, SELECT, UPDATE ON {schema}."DelayedTrigger" TO {role};
        CREATE UNIQUE INDEX "ixDelayedTrigger_EntityId" ON {schema}."DelayedTrigger" (EntityId);
        CREATE INDEX "ixDelayedTrigger_Deadline" ON {schema}."DelayedTrigger" (Deadline);

        CREATE TABLE {schema}."ConditionalTrigger"
        (
            Id                 UUID         NOT NULL,
            EntityId           UUID         NOT NULL,
            EntityModelId      UUID         NOT NULL,
            TriggerEntityId    UUID         NOT NULL,
            TriggerEventNumber INT          NOT NULL,
            Type               UUID         NOT NULL,
            Data               BYTEA,
            Timestamp          TIMESTAMP    NOT NULL,
            LastTypes          VARCHAR(370) NOT NULL,
            ReferenceType      UUID         NOT NULL,
            IndexKey           BYTEA        NOT NULL,
            CorrelationId      VARCHAR(36)  NOT NULL,
            CONSTRAINT "pkConditionalTrigger" PRIMARY KEY (Id)
        );
        GRANT INSERT, DELETE, SELECT ON {schema}."ConditionalTrigger" TO {role};
        """.replace("{schema}", quote(schema))
            .replace("{role}", quote(role));
  }

  /// Creates the schema, or verifies the checksum of the existing one. Completes with 0 if the schema exists.
  ///
  /// An existing schema is verified without a lock. Otherwise the check and the creation are done in a transaction
  /// holding an advisory lock on the schema name, so that state machines initialising at the same time do not compete
  /// for the creation.
  public Mono<Integer> execute(DataSource dataSource) {
    return Mono.fromCallable(() -> {
          try (Connection connection = dataSource.getConnection()) {
            // Restored, as a pool may not reset them when the connection is returned
            boolean autoCommit = connection.getAutoCommit();
            int isolation = connection.getTransactionIsolation();
            try {
              // The check without the lock is not done in a transaction, which would prevent changing the isolation
              connection.setAutoCommit(true);
              // A schema being created by another transaction is not visible until it is committed
              Long checksum = existingChecksum(connection);
              return checksum != null ? verified(checksum) : createLocked(connection);
            } finally {
              connection.setTransactionIsolation(isolation);
              connection.setAutoCommit(autoCommit);
            }
          }
        })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnNext(updateCount -> {
          if (updateCount == 0)
            System.out.println("Schema " + schema + " exists, checksum verified");
          else
            System.out.println("Schema " + schema + " created");
        });
  }

  private int createLocked(Connection connection) throws SQLException {
    // Each statement gets a new snapshot, so that the check after the lock sees a schema created while waiting for it
    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    connection.setAutoCommit(false);
    try {
      int result = createLockedInTransaction(connection);
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    }
  }

  private int createLockedInTransaction(Connection connection) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
      lock.setString(1, "CreateSchema " + schema);
      lock.executeQuery().close();
    }
    // Another state machine may have created the schema while this one waited for the lock
    Long checksum = existingChecksum(connection);
    if (checksum != null)
      return verified(checksum);
    try (Statement create = connection.createStatement()) {
      create.executeUpdate(createSql());
    }
    return 1;
  }

  private int verified(long checksum) {
    if (checksum != checksum())
      throw new RuntimeException("Checksum mismatch on schema " + schema + ". Used following clear text: " + coreSql());
    return 0;
  }

  /// The checksum of the existing schema, or null if it does not exist. The table is looked up in pg_tables, which is
  /// read with the statement's snapshot, rather than with to_regclass. That uses the session's catalog cache, which is
  /// not refreshed by getting an advisory lock, so it would miss a schema created while this session waited for the
  /// lock if it had looked the table up before. See "Seeing the schema after the lock on PostgreSQL" in
  /// docs/schema-creation.md.
  private Long existingChecksum(Connection connection) throws SQLException {
    try (PreparedStatement exists = connection.prepareStatement(
        "SELECT EXISTS (SELECT FROM pg_catalog.pg_tables WHERE schemaname = ? AND tablename = 'Metadata')"
    )) {
      exists.setString(1, schema);
      try (ResultSet rs = exists.executeQuery()) {
        rs.next();
        if (!rs.getBoolean(1))
          return null;
      }
    }
    try (
        Statement select = connection.createStatement();
        ResultSet rs = select.executeQuery("SELECT Checksum FROM " + table(schema, "Metadata"))
    ) {
      rs.next();
      return rs.getLong(1);
    }
  }

}
