package com.github.thxmasj.backend.database.mssql;

import com.github.thxmasj.backend.core.database.Client;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import reactor.core.publisher.Mono;

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

  public Mono<Integer> execute(Client databaseClient) {
    return databaseClient.sql("SELECT Checksum FROM [{schema}].Metadata".replace("{schema}", schema))
        .map(row -> row.get("Checksum", Long.class))
        .one()
        .onErrorResume((e) -> e.getCause().getMessage().equals("Invalid object name '" + schema + ".Metadata'.") ?
            Mono.empty() :
            Mono.error(e)
        )
        .flatMap(checksum -> checksum == checksum() ?
            Mono.just(0) :
            Mono.error(new RuntimeException("Checksum mismatch on schema " + schema + ". Used following clear text: " + coreSql()))
        )
        .switchIfEmpty(
            databaseClient.sql("CREATE SCHEMA [" + schema + "]").name("CreateSchema0").update()
                .onErrorResume(t -> t.getCause() != null && t.getCause().getMessage().equals("There is already an object named '" + schema + "' in the database."), _ -> Mono.just(0))
                .then(databaseClient.sql(createSql()).name("CreateSchema1").update())
        )
        .doOnNext(updateCount -> {
          if (updateCount == 0)
            System.out.println("Schema " + schema + " exists, checksum verified");
          else
            System.out.println("Schema " + schema + " created");
        });
  }

}
