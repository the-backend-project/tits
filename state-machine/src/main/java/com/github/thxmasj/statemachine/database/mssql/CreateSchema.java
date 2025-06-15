package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Subscriber;
import com.github.thxmasj.statemachine.database.Client;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import reactor.core.publisher.Mono;

@SuppressWarnings({"StringConcatenationInLoop"})
public class CreateSchema {

  private final List<EntityModel> entityModels;
  private final String schema;
  private final String role;

  public CreateSchema(List<EntityModel> entityModels, String schemaName, String role) {
    this.entityModels = entityModels;
    this.schema = schemaName;
    this.role = role;
  }

  public String sql() {
    return createSql(schema);
  }

  public long checksum() {
    return checksum(coreSql());
  }

  private long checksum(String coreSql) {
    var crc32 = new CRC32();
    crc32.update(coreSql.getBytes(StandardCharsets.UTF_8));
    return crc32.getValue();
  }

  private String coreSql() {
    StringBuilder sql = new StringBuilder();
    for (var entityType : entityModels) {
      sql.append(sqlForEntity(entityType));
    }
    return sql.toString();
  }

  private String createSql(String schema) {
    String coreSql = coreSql();
    String initSql =
        """
        CREATE TABLE [{schema}].[Metadata] (Checksum BIGINT NOT NULL)
        INSERT INTO [{schema}].[Metadata] (Checksum) VALUES ({checksum})
        """.replace("{schema}", schema)
            .replace("{checksum}", String.valueOf(checksum(coreSql)));
    return initSql + coreSql;
  }

  private String sqlForEntity(EntityModel entityModel) {
    var entity = entityModel.name();
    var names = new SchemaNames(schema, entityModel);
    var q = names.qualifiedNames();
    String sql =
        """
        CREATE TABLE [{schema}].[{entity}Event]
        (
            EntityId    UNIQUEIDENTIFIER   NOT NULL,
            EventNumber SMALLINT           NOT NULL,
            Type        VARCHAR(1024)      NOT NULL,
            MessageId   VARCHAR(100),
            ClientId    VARCHAR(100),
            Data        VARCHAR(MAX),
            Timestamp   DATETIME2          NOT NULL,
            CONSTRAINT [pk{entity}Event] PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[{entity}Event] TO [{role}];
        CREATE UNIQUE INDEX ixEntityId_EventNumber ON [{schema}].[{entity}Event] (EntityId, EventNumber);

        CREATE TABLE [{schema}].[{entity}Timeout]
        (
            EntityId      UNIQUEIDENTIFIER  NOT NULL,
            EventNumber   SMALLINT          NOT NULL,
            Deadline      DATETIME2(2)      NOT NULL,
            CorrelationId VARCHAR(36)       NOT NULL,
            Attempt       INT               NOT NULL
        );
        GRANT INSERT, DELETE, SELECT, UPDATE ON [{schema}].[{entity}Timeout] TO [{role}];
        CREATE UNIQUE INDEX ixEntityId ON [{schema}].[{entity}Timeout] (EntityId);
        CREATE INDEX ixDeadline ON [{schema}].[{entity}Timeout] (Deadline);

        CREATE TABLE [{schema}].[{entity}Inbox]
        (
            Id                   UNIQUEIDENTIFIER NOT NULL,
            EntityId             UNIQUEIDENTIFIER NOT NULL,
            EventNumber          SMALLINT         NOT NULL,
            Timestamp            DATETIME2        NOT NULL,
            MessageId            VARCHAR(100),
            ClientId             VARCHAR(100)     NOT NULL,
            Digest               BINARY(32),
            Data                 VARCHAR(MAX)     NOT NULL,
            CONSTRAINT [pk{entity}Inbox] PRIMARY KEY (Id),
            CONSTRAINT [fk{entity}Inbox_{entity}Event_EntityId_EventNumber] FOREIGN KEY (EntityId, EventNumber) references [{schema}].[{entity}Event] (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[{entity}Inbox] TO [{role}];
        CREATE UNIQUE INDEX ixMessageId_ClientId ON [{schema}].[{entity}Inbox] (MessageId, ClientId)
            WHERE [MessageId] IS NOT NULL AND [ClientId] IS NOT NULL;

        CREATE TABLE [{schema}].[{entity}Outbox]
        (
            EntityId             UNIQUEIDENTIFIER NOT NULL,
            EventNumber          SMALLINT         NOT NULL,
            Timestamp            DATETIME2        NOT NULL,
            Data                 VARCHAR(MAX)     NOT NULL,
            RequestId            UNIQUEIDENTIFIER NOT NULL,
            CONSTRAINT [pk{entity}Outbox] PRIMARY KEY (EntityId, EventNumber),
            CONSTRAINT [fk{entity}Outbox_{entity}Inbox_RequestId] FOREIGN KEY (RequestId) references [{schema}].[{entity}Inbox] (Id)
        );
        GRANT INSERT, SELECT ON [{schema}].[{entity}Outbox] TO [{role}];
        """.replace("{schema}", schema)
            .replace("{entity}", entity)
            .replace("{role}", role);
        for (var secondaryId : entityModel.secondaryIds()) {
          String tableName = entity + "_" + secondaryId.name();
          String qualifiedTableName = q.idTable(secondaryId);
          sql +=
              """
              CREATE TABLE {qualifiedTableName}
              (
                EntityId UNIQUEIDENTIFIER NOT NULL,
                {serialNumberColumnDefinition}
                {columnDefinitions}
                CONSTRAINT [pk{tableName}] PRIMARY KEY (EntityId)
              );
              GRANT INSERT, SELECT ON {qualifiedTableName} TO [{role}];
              CREATE UNIQUE INDEX {indexName} ON {qualifiedTableName} ({columnList});
              """.replace("{qualifiedTableName}", qualifiedTableName)
                  .replace("{role}", role)
                  .replace("{tableName}", tableName)
                  .replace("{serialNumberColumnDefinition}", secondaryId.isSerial() ? "SerialNumber BIGINT NOT NULL," : "")
                  .replace("{columnDefinitions}", secondaryId.columns().stream().map(column -> column.name() + " " + column.type() + " NOT NULL").collect(joining(",")))
                  .replace("{indexName}", names.indexName(secondaryId.columns()))
                  .replace("{columnList}", String.join(",", secondaryId.columns().stream().map(SchemaNames.Column::name).toList()))
          ;
        }
        for (Subscriber subscriber : entityModel.subscribers().stream().toList()) {
          sql +=
              """
              CREATE TABLE {outboxTable}
              (
                  Id                 BIGINT           IDENTITY,
                  EntityId           UNIQUEIDENTIFIER NOT NULL,
                  EventNumber        SMALLINT         NOT NULL,
                  Timestamp          DATETIME2        NOT NULL,
                  Data               VARCHAR(MAX)     NOT NULL,
                  CONSTRAINT [{outboxTablePK}] PRIMARY KEY (Id),
                  CONSTRAINT [{outboxTableToEventTableFK}] FOREIGN KEY (EntityId, EventNumber) REFERENCES {eventTable} (EntityId, EventNumber)
              );
              GRANT INSERT, SELECT ON {outboxTable} TO [{role}];

              CREATE TABLE {inboxTable}
              (
                  EntityId           UNIQUEIDENTIFIER NOT NULL,
                  EventNumber        SMALLINT         NOT NULL,
                  Timestamp          DATETIME2        NOT NULL,
                  Data               VARCHAR(MAX)     NOT NULL,
                  RequestId          BIGINT           NOT NULL,
                  CONSTRAINT [{inboxTableToOutboxTableFK}] FOREIGN KEY (RequestId) REFERENCES {outboxTable} (Id)
              );
              GRANT INSERT, SELECT ON {inboxTable} TO [{role}];

              CREATE TABLE {queueTable}
              (
                  ElementId       ROWVERSION,
                  EntityId        UNIQUEIDENTIFIER NOT NULL,
                  EventNumber     SMALLINT         NOT NULL,
                  CreatorId       UNIQUEIDENTIFIER NOT NULL,
                  Timestamp       DATETIME2        NOT NULL,
                  Data            VARCHAR(MAX),
                  ParentEntityId  UNIQUEIDENTIFIER NULL,
                  Guaranteed      BIT              NOT NULL,
                  CorrelationId   VARCHAR(36)      NOT NULL,
                  OutboxElementId BIGINT           NOT NULL,
                  CONSTRAINT [{queueTablePK}] PRIMARY KEY (ElementId),
                  CONSTRAINT [{queueTableToOutboxTableFK}] FOREIGN KEY (OutboxElementId) REFERENCES {outboxTable} (Id)
              );
              GRANT INSERT, SELECT, DELETE ON {queueTable} TO [{role}];
              CREATE INDEX ixEntityId ON {queueTable} (EntityId);

              CREATE TABLE {dlqTable}
              (
                  EntityId    UNIQUEIDENTIFIER NOT NULL,
                  EventNumber SMALLINT         NOT NULL,
                  Cause       VARCHAR(MAX),
                  CONSTRAINT [{dlqTablePK}] PRIMARY KEY (EntityId)
              );
              GRANT INSERT, SELECT ON {dlqTable} TO [{role}];

              CREATE TABLE {processingTable}
              (
                  ElementId        BINARY(8)        NOT NULL,
                  EntityId         UNIQUEIDENTIFIER NOT NULL,
                  EventNumber      SMALLINT         NOT NULL,
                  CreatorId        UNIQUEIDENTIFIER NOT NULL,
                  Data             VARCHAR(MAX)     NOT NULL,
                  EnqueuedAt       DATETIME2        NOT NULL,
                  Attempt          INT              NOT NULL,
                  NextAttemptAt    DATETIME2        NOT NULL,
                  Guaranteed       BIT              NOT NULL,
                  CorrelationId    VARCHAR(36)      NOT NULL,
                  OutboxElementId  BIGINT           NOT NULL,
                  CONSTRAINT [{processingTablePK}] PRIMARY KEY (EntityId)
              )
              GRANT INSERT, SELECT, UPDATE, DELETE ON {processingTable} TO [{role}];
              CREATE INDEX ixNextAttemptAt ON {processingTable} (NextAttemptAt ASC);
              """.replace("{outboxTable}", q.outboxTable(subscriber))
                  .replace("{outboxTableToEventTableFK}", String.format("fk%s_%s_EntityId_EventNumber", names.outboxTableName(subscriber), names.eventTableName()))
                  .replace("{outboxTablePK}", names.outboxTablePrimaryKeyName(subscriber))
                  .replace("{eventTable}", q.eventTable())
                  .replace("{inboxTable}", q.inboxTable(subscriber))
                  .replace("{inboxTableToOutboxTableFK}", String.format("fk%s_%s_Id)", names.inboxTableName(subscriber), names.outboxTableName(subscriber)))
                  .replace("{queueTable}", q.queueTable(subscriber))
                  .replace("{queueTablePK}", names.queueTablePrimaryKeyName(subscriber))
                  .replace("{dlqTable}", q.dlqTable(subscriber))
                  .replace("{dlqTablePK}", names.dlqTablePrimaryKeyName(subscriber))
                  .replace("{processingTable}", q.processingTable(subscriber))
                  .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(subscriber))
                  .replace("{queueTableToOutboxTableFK}", String.format("fk%s_%s_EntityId_EventNumber_Type", names.queueTableName(subscriber), names.outboxTableName(subscriber)))
                  .replace("{role}", role)
          ;
        }
        return sql;
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
            Mono.error(new RuntimeException("Checksum mismatch on schema " + schema))
        )
        .switchIfEmpty(
            databaseClient.sql("CREATE SCHEMA [" + schema + "]").name("CreateSchema0").update()
                .onErrorResume(t -> t.getCause() != null && t.getCause().getMessage().equals("There is already an object named '" + schema + "' in the database."), _ -> Mono.just(0))
                .then(databaseClient.sql(createSql(schema)).name("CreateSchema1").update())
        )
        .doOnNext(updateCount -> {
          if (updateCount == 0)
            System.out.println("Schema " + schema + " exists, checksum verified");
          else
            System.out.println("Schema " + schema + " created");
        });
  }

}
