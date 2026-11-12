package com.github.thxmasj.backend.database.mssql;

import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
        CREATE TABLE [{schema}].[Metadata] (Checksum BIGINT NOT NULL)
        INSERT INTO [{schema}].[Metadata] (Checksum) VALUES ({checksum})
        """.replace("{schema}", schema)
            .replace("{checksum}", String.valueOf(checksum()))
        + coreSql();
  }

  /// The tables, as verified by the checksum
  private String coreSql() {
    return
        """
        CREATE TABLE [{schema}].[Entity]
        (
            EntityId      UNIQUEIDENTIFIER NOT NULL,
            EntityModelId UNIQUEIDENTIFIER NOT NULL,
            CONSTRAINT [pkEntity] PRIMARY KEY (EntityId)
        );
        GRANT INSERT, SELECT ON [{schema}].[Entity] TO [{role}];

        CREATE TABLE [{schema}].[Event]
        (
            EntityId    UNIQUEIDENTIFIER   NOT NULL,
            EventNumber SMALLINT           NOT NULL,
            Type        UNIQUEIDENTIFIER   NOT NULL,
            Data        VARBINARY(MAX),
            --Data        VARCHAR(MAX),
            Timestamp   DATETIME2          NOT NULL,
            CONSTRAINT [pkEvent] PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[Event] TO [{role}];
        --CREATE UNIQUE INDEX ixEntityId_EventNumber ON [{schema}].[Event] (EntityId, EventNumber);

        CREATE TABLE [{schema}].[IndexEvent]
        (
            EntityId    UNIQUEIDENTIFIER   NOT NULL,
            EventNumber SMALLINT           NOT NULL,
            Type        UNIQUEIDENTIFIER   NOT NULL,
            --Data        VARCHAR(1700)    NOT NULL,
            -- Leaves room for Type in the 1700 bytes of the index key
            Data        VARBINARY(1684)    NOT NULL,
            Timestamp   DATETIME2          NOT NULL,
            CONSTRAINT [pkIndexEvent] PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[IndexEvent] TO [{role}];
        --CREATE UNIQUE INDEX ixEntityId_EventNumber ON [{schema}].[IndexEvent] (EntityId, EventNumber);
        -- Finds the entities indexed by a key, and the index events of a group in the order of their data
        CREATE INDEX ixType_Data ON [{schema}].[IndexEvent] (Type, Data);

        CREATE TABLE [{schema}].[IdEvent]
        (
            EntityId    UNIQUEIDENTIFIER   NOT NULL,
            EventNumber SMALLINT           NOT NULL,
            Type        UNIQUEIDENTIFIER   NOT NULL,
            -- Leaves room for Type in the 1700 bytes of the unique index key
            Data        VARBINARY(1684)    NOT NULL,
            Timestamp   DATETIME2          NOT NULL,
            CONSTRAINT [pkIdEvent] PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[IdEvent] TO [{role}];
        CREATE UNIQUE INDEX ixType_Data ON [{schema}].[IdEvent] (Type, Data);

        CREATE TABLE [{schema}].[DelayedTrigger]
        (
            EntityId      UNIQUEIDENTIFIER NOT NULL,
            EntityModelId UNIQUEIDENTIFIER NOT NULL,
            EventNumber   SMALLINT         NOT NULL,
            Type          UNIQUEIDENTIFIER NOT NULL,
            Data          VARBINARY(MAX),
            --Data          VARCHAR(MAX),
            Deadline      DATETIME2(2)     NOT NULL,
            CorrelationId VARCHAR(36)      NOT NULL,
            Attempt       INT              NOT NULL
        );
        GRANT INSERT, DELETE, SELECT, UPDATE ON [{schema}].[DelayedTrigger] TO [{role}];
        CREATE UNIQUE INDEX ixEntityId ON [{schema}].[DelayedTrigger] (EntityId);
        CREATE INDEX ixDeadline ON [{schema}].[DelayedTrigger] (Deadline);

        CREATE TABLE [{schema}].[ConditionalTrigger]
        (
            Id            UNIQUEIDENTIFIER NOT NULL,
            EntityId      UNIQUEIDENTIFIER NOT NULL,
            EntityModelId UNIQUEIDENTIFIER NOT NULL,
            TriggerEntityId    UNIQUEIDENTIFIER NOT NULL,
            TriggerEventNumber INT              NOT NULL,
            Type          UNIQUEIDENTIFIER NOT NULL,
            Data          VARBINARY(MAX),
            Timestamp     DATETIME2        NOT NULL,
            LastTypes     VARCHAR(370)     NOT NULL, -- Comma separated event type ids, at most 10
            ReferenceType UNIQUEIDENTIFIER NOT NULL,
            IndexKey      VARBINARY(1700)  NOT NULL,
            CorrelationId VARCHAR(36)      NOT NULL,
            CONSTRAINT [pkConditionalTrigger] PRIMARY KEY (Id)
        );
        GRANT INSERT, DELETE, SELECT ON [{schema}].[ConditionalTrigger] TO [{role}];
        """.replace("{schema}", schema)
            .replace("{role}", role);
  }

  /// Creates the schema, or verifies the checksum of the existing one. Completes with 0 if the schema exists.
  ///
  /// An existing schema is verified without a lock. Otherwise the check and the creation are done holding an
  /// application lock on the schema name, so that state machines initialising at the same time do not compete for the
  /// creation. The creation is done in a transaction.
  public Mono<Integer> execute(DataSource dataSource) {
    return Mono.fromCallable(() -> {
          try (Connection connection = dataSource.getConnection()) {
            // Restored, as a pool may not reset it when the connection is returned
            boolean autoCommit = connection.getAutoCommit();
            try {
              // The check without the lock is not done in a transaction
              connection.setAutoCommit(true);
              Long checksum = unlockedChecksum(connection);
              return checksum != null ? verified(checksum) : createLocked(connection);
            } finally {
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

  /// The checksum of the existing schema, or null if it does not exist or can not be read without the lock. OBJECT_ID
  /// finds a schema being created by another transaction before it is committed. Reading its checksum then waits for
  /// the transaction, and fails if it is rolled back, which leaves the decision to the locked check.
  private Long unlockedChecksum(Connection connection) {
    try {
      return existingChecksum(connection);
    } catch (SQLException e) {
      return null;
    }
  }

  private int createLocked(Connection connection) throws SQLException {
    // The lock is owned by the session, as a procedure call does not begin an implicit transaction
    applock(connection, "sp_getapplock", "{? = call sp_getapplock(?, 'Exclusive', 'Session')}");
    try {
      connection.setAutoCommit(false);
      int result = createLockedInTransaction(connection);
      connection.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      connection.rollback();
      throw e;
    } finally {
      // Released before the auto-commit mode is restored, so that it is not left on a pooled connection if that fails
      applock(connection, "sp_releaseapplock", "{? = call sp_releaseapplock(?, 'Session')}");
    }
  }

  /// Gets or releases the application lock on the schema name. Getting it waits until the query times out.
  private void applock(Connection connection, String procedure, String call) throws SQLException {
    try (CallableStatement statement = connection.prepareCall(call)) {
      statement.registerOutParameter(1, Types.INTEGER);
      statement.setString(2, "CreateSchema " + schema);
      statement.execute();
      if (statement.getInt(1) < 0)
        throw new RuntimeException(procedure + " on schema " + schema + " failed with " + statement.getInt(1));
    }
  }

  private int createLockedInTransaction(Connection connection) throws SQLException {
    // Another state machine may have created the schema while this one waited for the lock
    Long checksum = existingChecksum(connection);
    if (checksum != null)
      return verified(checksum);
    try (Statement create = connection.createStatement()) {
      if (!exists(connection, "SELECT SCHEMA_ID(?)", schema))
        create.executeUpdate("CREATE SCHEMA [" + schema + "]");
      create.executeUpdate(createSql());
    }
    return 1;
  }

  private int verified(long checksum) {
    if (checksum != checksum())
      throw new RuntimeException("Checksum mismatch on schema " + schema + ". Used following clear text: " + coreSql());
    return 0;
  }

  /// The checksum of the existing schema, or null if it does not exist
  private Long existingChecksum(Connection connection) throws SQLException {
    if (!exists(connection, "SELECT OBJECT_ID(?, 'U')", "[" + schema + "].[Metadata]"))
      return null;
    try (
        Statement select = connection.createStatement();
        ResultSet rs = select.executeQuery("SELECT Checksum FROM [" + schema + "].[Metadata]")
    ) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /// Whether the query of an object's id finds the named object
  private static boolean exists(Connection connection, String sql, String name) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, name);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        rs.getObject(1);
        return !rs.wasNull();
      }
    }
  }

}
