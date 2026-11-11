package com.github.thxmasj.backend.database.pgsql;

import static com.github.thxmasj.backend.database.pgsql.Names.quote;
import static com.github.thxmasj.backend.database.pgsql.Names.table;

import com.github.thxmasj.backend.core.database.Client;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Set;
import java.util.zip.CRC32;
import reactor.core.publisher.Mono;

/// The same tables as for SQL Server, with PostgreSQL types. Index names are prefixed with their table, as they are
/// unique within the schema and not only within the table.
public class CreateSchema {

  // undefined_table and invalid_schema_name
  private static final Set<String> missingSchemaStates = Set.of("42P01", "3F000");

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

  public Mono<Integer> execute(Client databaseClient) {
    return databaseClient.sql("SELECT Checksum FROM " + table(schema, "Metadata"))
        .map(row -> row.get("Checksum", Long.class))
        .one()
        .onErrorResume(
            e -> e.getCause() instanceof SQLException s && missingSchemaStates.contains(s.getSQLState()),
            _ -> Mono.empty()
        )
        .flatMap(checksum -> checksum == checksum() ?
            Mono.just(0) :
            Mono.error(new RuntimeException("Checksum mismatch on schema " + schema + ". Used following clear text: " + coreSql()))
        )
        // The statements are executed in a single implicit transaction
        .switchIfEmpty(databaseClient.sql(createSql()).name("CreateSchema").update().thenReturn(1))
        .doOnNext(updateCount -> {
          if (updateCount == 0)
            System.out.println("Schema " + schema + " exists, checksum verified");
          else
            System.out.println("Schema " + schema + " created");
        });
  }

}
