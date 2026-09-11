package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;

import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;
import reactor.core.publisher.Mono;

@SuppressWarnings({"StringConcatenationInLoop"})
public class CreateSchema {

  private final List<SecondaryIdModel<?>> idModels;
  private final String schema;
  private final String role;

  public CreateSchema(List<SecondaryIdModel<?>> idModels, String schemaName, String role) {
    this.idModels = idModels.stream().sorted(Comparator.comparing(SecondaryIdModel::id)).toList();
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
    return sqlForIds(idModels);
  }

  private String createSql(String schema) {
    String coreSql = coreSql();
    String initSql =
        """
        CREATE TABLE [{schema}].[Metadata] (Checksum BIGINT NOT NULL)
        INSERT INTO [{schema}].[Metadata] (Checksum) VALUES ({checksum})

        CREATE TABLE [{schema}].[Event]
        (
            EntityId    UNIQUEIDENTIFIER   NOT NULL,
            EventNumber SMALLINT           NOT NULL,
            Type        UNIQUEIDENTIFIER   NOT NULL,
            Data        VARCHAR(MAX),
            Timestamp   DATETIME2          NOT NULL,
            CONSTRAINT [pkEvent] PRIMARY KEY (EntityId, EventNumber)
        );
        GRANT INSERT, SELECT ON [{schema}].[Event] TO [{role}];
        CREATE UNIQUE INDEX ixEntityId_EventNumber ON [{schema}].[Event] (EntityId, EventNumber);

        CREATE TABLE [{schema}].[Timeout]
        (
            EntityId      UNIQUEIDENTIFIER NOT NULL,
            EntityModelId UNIQUEIDENTIFIER NOT NULL,
            EventNumber   SMALLINT         NOT NULL,
            Type          UNIQUEIDENTIFIER NOT NULL,
            Data          VARCHAR(MAX),
            Deadline      DATETIME2(2)     NOT NULL,
            CorrelationId VARCHAR(36)      NOT NULL,
            Attempt       INT              NOT NULL
        );
        GRANT INSERT, DELETE, SELECT, UPDATE ON [{schema}].[Timeout] TO [{role}];
        CREATE UNIQUE INDEX ixEntityId ON [{schema}].[Timeout] (EntityId);
        CREATE INDEX ixDeadline ON [{schema}].[Timeout] (Deadline);
        """.replace("{schema}", schema)
            .replace("{checksum}", String.valueOf(checksum(coreSql)))
            .replace("{role}", role);
    return initSql + coreSql;
  }

  private String sqlForIds(List<SecondaryIdModel<?>> ids) {
    var names = new SchemaNames(schema, null);
    String sql = "";
        for (SecondaryIdModel<?> secondaryId : ids) {
          String tableName = "Id_" + secondaryId.name();
          String qualifiedTableName = String.format("[%s].[%s]", schema, tableName);
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
            Mono.error(new RuntimeException("Checksum mismatch on schema " + schema + ". Used following clear text: " + coreSql()))
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
