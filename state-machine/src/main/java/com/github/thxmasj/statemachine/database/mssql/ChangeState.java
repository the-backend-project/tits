package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.statemachine.database.Client.Query.Builder;
import com.github.thxmasj.statemachine.database.Client.UniqueIndexConstraintViolation;
import com.github.thxmasj.statemachine.database.EventAlreadyExists;
import com.github.thxmasj.statemachine.database.SecondaryIdAlreadyExists;
import com.github.thxmasj.statemachine.http.outbox.HttpOutbox;
import com.github.thxmasj.statemachine.http.outbox.RequestEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;

public class ChangeState {

  private final List<EntityModel> entityModels;
  private final Client databaseClient;
  private final Clock clock;
  private final String schema;

  public ChangeState(List<EntityModel> entityModels, Client databaseClient, String schema, Clock clock) {
    this.entityModels = entityModels;
    this.databaseClient = databaseClient;
    this.clock = clock;
    this.schema = schema;
  }

  private void bind(Builder spec, ZonedDateTime timestamp, String correlationId, Change change) {
    Event<?> event = change.newEvent();
    ZonedDateTime deadline = change.timeout() != null ? timestamp.plus(change.timeout()) : null;
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId().value());
    spec.bind("entityModelId", change.entityModel().id());
    spec.bind("timestamp", timestamp.withZoneSameInstant(clock.getZone()).toLocalDateTime());
    if (event != null) {
      spec.bind("eventNumber", event.eventNumber())
          .bind("type", event.type().id())
          .bind("data", event.getMarshalledData());
    }
    for (SecondaryId<?> secondaryId : change.newSecondaryIds()) {
      for (int i = 0; i < secondaryId.model().columns().size(); i++) {
        spec.bind(
            "secondaryId" + secondaryId.model().name() + i,
            secondaryId.model().columns().get(i).value().apply(secondaryId.data())
        );
      }
      if (secondaryId.model().isSerial())
        spec.bind("secondaryId" + secondaryId.model().name() + "SerialNumber", secondaryId.serialNumber());
    }
//    for (int i = 0; i < change.outgoingRequests().size(); i++) {
//      OutgoingRequest orq = change.outgoingRequests().get(i);
//      if (orq.parentEntity() != null) {
//        spec.bind("outgoingRequestParentEntityId" + i, orq.parentEntity().value());
//      }
//      spec.bind("outgoingRequestId" + i, orq.id())
//          .bind("outgoingRequestQueueId" + i, orq.queue().id())
//          .bind("outgoingRequestEventNumber" + i, orq.eventNumber())
//          .bind("outgoingRequestCreatorId" + i, orq.creatorId())
//          .bind("correlationId", correlationId)
//          .bind("outgoingRequestGuaranteedDelivery" + i, orq.guaranteed())
//          .bind("outgoingRequestData" + i, orq.message().message());
//    }
//    if (change.incomingResponse() != null) {
//      IncomingResponse irs = change.incomingResponse();
//      spec.bind("incomingResponseEventNumber", irs.eventNumber())
//          .bind("incomingResponseRequestId", irs.requestId())
//          .bind("incomingResponseData", irs.message().message());
//    }
    if (deadline != null) {
      spec
          .bind("deadline", deadline.withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  public interface Change {

    EntityModel entityModel();
    
    EntityId entityId();
    
    Event<?> newEvent();

    List<SecondaryId<?>> newSecondaryIds();

    Duration timeout();

    static Change fromAcceptedEvent(ProcessResult.Accepted<?> acceptedEvent) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return acceptedEvent.entityModel();
        }

        @Override
        public EntityId entityId() {
          return new EntityId.UUID(acceptedEvent.event().entityId());
        }

        @Override
        public Event<?> newEvent() {
          return acceptedEvent.event();
        }

        @Override
        public List<SecondaryId<?>> newSecondaryIds() {
          return List.of();
        }

        @Override
        public Duration timeout() {
          return acceptedEvent.timeout();
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              entityId().value() + ":ev:[" +
              newEvent().typeName() + "]:" +
              newEvent().eventNumber() +
              (newEvent().type() instanceof BasicEventType.ReadOnly<?,?> ? " (read-only)" : "");
        }

      };
    }

    static Change fromIdentifier(SecondaryId<?> id, EntityModel entityModel, EntityId entityId) {
      return new Change() {
        @Override
        public EntityModel entityModel() {
          return entityModel;
        }

        @Override
        public EntityId entityId() {
          return entityId;
        }

        @Override
        public Event<?> newEvent() {
          return null;
        }

        @Override
        public List<SecondaryId<?>> newSecondaryIds() {
          return List.of(id);
        }

        @Override
        public Duration timeout() {
          return null;
        }

        @Override
        public String toString() {
          return entityModel().name() + ":" +
              entityId().value() + ":id:" +
              id.model().name() + ":" +
              id.data();
        }
      };
    }

  }

  public record OutboxElement(int changeIndex, int messageIndex, UUID requestId, byte[] elementId) {}

  public Flux<OutboxElement> execute(ZonedDateTime timestamp, String correlationId, List<Change> changes) {
    String sql =
        """
        SET XACT_ABORT ON;
        BEGIN TRANSACTION;
        DECLARE @OutboxElement         TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER);
        DECLARE @QueueElement          TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER, ElementId BINARY(8), Guaranteed BIT);
        DECLARE @QueueElementToProcess TABLE (ChangeIndex TINYINT, MessageIndex TINYINT, RequestId UNIQUEIDENTIFIER, ElementId BINARY(8));
        """ + IntStream.range(0, changes.size()).mapToObj(i -> insertSql(
            i,
            "p" + i + "_",
            changes.get(i).entityModel(),
            changes.get(i).newEvent(),
            changes.get(i).newSecondaryIds(),
//            changes.get(i).outgoingRequests(),
//            changes.get(i).incomingResponse(),
            changes.get(i).timeout() != null
        )).collect(joining("\n")) +
            """
            INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
            WHERE Guaranteed = 0;
            
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
            COMMIT TRANSACTION;
            """;
    Builder spec = databaseClient.sql(sql).name("ChangeState");
    for (int i = 0; i < changes.size(); i++) {bind(spec.parameterPrefix("p" + i + "_"), timestamp, correlationId, changes.get(i));}
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("MessageIndex", Integer.class),
            row.get("RequestId", UUID.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(PrimaryKeyConstraintViolation.class, primaryKeyConstraintViolationMapper(changes))
        .onErrorMap(UniqueIndexConstraintViolation.class, uniqueIndexConstraintViolationMapper(changes))
        ;
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      EntityModel entityModel,
      Event<?> event,
      List<SecondaryId<?>> secondaryIds,
//      List<OutgoingRequest> outgoingRequests,
//      IncomingResponse incomingResponse,
      boolean withDeadline
  ) {
    String sql =
        """
        DECLARE @entityId{changeIndex} UNIQUEIDENTIFIER
        SELECT @entityId{changeIndex}=:entityId
        """.replace("{changeIndex}", String.valueOf(changeIndex));
    //
    // Secondary id
    //
    for (SecondaryId<?> secondaryId : secondaryIds) {
      sql +=
          """
          INSERT INTO [{schema}].[{idTable}] (EntityId, {columnList})
          VALUES (@entityId{changeIndex}, {valueList})
          """.replace("{idTable}", entityModel.name() + "_" + secondaryId.model().name())
              .replace(
                  "{columnList}",
                  secondaryId.model().columns().stream().map(SchemaNames.Column::name).collect(joining(",")) + (
                      secondaryId.model().isSerial() ? ",SerialNumber" : "")
              )
              .replace("{changeIndex}", String.valueOf(changeIndex))
              .replace(
                  "{valueList}",
                  range(0, secondaryId.model().columns().size()).mapToObj(i -> ":secondaryId" + secondaryId.model()
                      .name() + i).collect(joining(",")) + (secondaryId.model().isSerial() ? ",:secondaryId"
                      + secondaryId.model().name() + "SerialNumber" : "")
              )
              .replace("{schema}", schema);
    }

    //
    // 'Normal' event
    //
    if (event != null && !(entityModel instanceof HttpOutbox.EntityModel)) {
      sql +=
          """
          DELETE [{schema}].[Timeout] FROM [{schema}].[Timeout] WITH (INDEX([ixEntityId]))
          WHERE EntityId=@entityId{changeIndex} AND EventNumber=:eventNumber - 1;
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
      sql +=
          """
          INSERT INTO [{schema}].[Event] (
            EntityId,
            EventNumber,
            Type,
            Timestamp,
            Data
          ) VALUES (
            @entityId{changeIndex},
            :eventNumber,:type,:timestamp,:data
          )
          """.replace("{schema}", schema)
              .replace("{changeIndex}", String.valueOf(changeIndex));
    }

    //
    // HttpOutbox incoming response event
    //
//    if (event != null && entityModel instanceof HttpOutbox.EntityModel && event.type() == HttpOutbox.Response) {
//      sql +=
//        /* Deletes corresponding outgoing request from queue when incoming response has arrived. Note that this must
//           be done prior to insertion of new outgoing requests (see below), otherwise, in case of rollback, the new
//           outgoing request will be deleted as well */
//          """
//          DELETE [{schema}].[OutboxQueue] FROM [{schema}].[OutboxQueue] WITH (INDEX([ixEntityId]))
//          WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId
//          IF @@ROWCOUNT != 1
//            THROW 50003, 'Failed to delete outgoing request from queue', 1;
//          """.replace("{changeIndex}", String.valueOf(changeIndex))
//              .replace("{schema}", schema);
//    }

    //
    // HttpOutbox outgoing request event
    //
//    if (event != null && entityModel instanceof HttpOutbox.EntityModel outEntityModel && event.type() instanceof RequestEventType) {
//    sql += range(0, outgoingRequests.size()).mapToObj(i ->
//        """
//        INSERT INTO [{schema}].[OutboxRequest] (
//          Id,
//          QueueId,
//          EntityId,
//          EventNumber,
//          Timestamp,
//          Data
//        )
//        OUTPUT {changeIndex}, {i}, inserted.Id INTO @OutboxElement
//        VALUES (
//          :outgoingRequestId{i},
//          :outgoingRequestQueueId{i},
//          @entityId{changeIndex},
//          :outgoingRequestEventNumber{i},
//          :timestamp,
//          :outgoingRequestData{i}
//        );
//        """.replace("{changeIndex}", String.valueOf(changeIndex))
//            .replace("{i}", String.valueOf(i))
//            .replace("{schema}", schema)
//    ).collect(joining());

//      sql += //range(0, outgoingRequests.size()).mapToObj(i ->
//          """
//          INSERT INTO [{schema}].[OutboxQueue] (
//            QueueId,
//            EntityModelId,
//            EntityId,
//            {parentEntityColumn}
//            EventNumber,
//            CreatorId,
//            Guaranteed,
//            CorrelationId,
//            Timestamp,
//            RequestId
//          )
//          OUTPUT {changeIndex}, {i}, inserted.RequestId, inserted.ElementId, inserted.Guaranteed INTO @QueueElement
//          VALUES (
//            :outgoingRequestQueueId{i},
//            :entityModelId,
//            @entityId{changeIndex},
//            {parentEntityValue}
//            :outgoingRequestEventNumber{i},
//            :outgoingRequestCreatorId{i},
//            :outgoingRequestGuaranteedDelivery{i},
//            :correlationId,
//            :timestamp,
//            (SELECT RequestId FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND MessageIndex={i})
//          );
//          """.replace("{changeIndex}", String.valueOf(changeIndex))
//              .replace("{i}", String.valueOf(0))
//              .replace("{schema}", schema)
//              .replace("{parentEntityColumn}", outEntityModel.parentEntity() != null ? "ParentEntityId," : "")
//              .replace(
//                  "{parentEntityValue}",
//                  outEntityModel.parentEntity() != null ? ":outgoingRequestParentEntityId" + 0 + "," : ""
//              );
//      if (outEntityModel.guaranteed()) {
//        sql += //range(0, outgoingRequests.size()).filter(i -> outgoingRequests.get(i).guaranteed()).mapToObj(i ->
//            """
//            SET XACT_ABORT OFF
//            BEGIN TRY
//            INSERT INTO [{schema}].[OutboxQueueProcessing] (
//              ElementId,
//              QueueId,
//              EntityModelId,
//              EntityId,
//              EventNumber,
//              CreatorId,
//              Guaranteed,
//              Data,
//              CorrelationId,
//              EnqueuedAt,
//              Attempt,
//              NextAttemptAt,
//              RequestId
//            )
//            OUTPUT {changeIndex}, {i}, inserted.RequestId, inserted.ElementId INTO @QueueElementToProcess
//            SELECT
//              (SELECT ElementId FROM @QueueElement WHERE ChangeIndex = {changeIndex} AND MessageIndex = {i}),
//              :outgoingRequestQueueId{i},
//              :entityModelId,
//              @entityId{changeIndex},
//              :outgoingRequestEventNumber{i},
//              :outgoingRequestCreatorId{i},
//              :outgoingRequestGuaranteedDelivery{i},
//              :outgoingRequestData{i},
//              :correlationId,
//              :timestamp,
//              1,
//              (DATEADD(millisecond, 10*1000, :timestamp)), -- TODO: (DATEADD(millisecond, :minimumBackoff*1000, :now))
//              (SELECT RequestId FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND MessageIndex={i})
//            WHERE @entityId{changeIndex} NOT IN (
//              SELECT EntityId
//              FROM [{schema}].[OutboxDeadLetterQueue]
//              WITH (INDEX([pkOutboxDeadLetterQueue]))
//              WHERE QueueId=:outgoingRequestQueueId{i}
//            )
//            AND @entityId{changeIndex} NOT IN (
//              SELECT EntityId
//              FROM [{schema}].[OutboxQueueProcessing]
//              WITH (INDEX([pkOutboxQueueProcessing]))
//              WHERE QueueId=:outgoingRequestQueueId{i}
//            )
//            """.replace("{changeIndex}", String.valueOf(changeIndex))
//                .replace("{i}", String.valueOf(0))
//                .replace("{schema}", schema)
//                +
//                (
//                    childEntity(outEntityModel) == null ? "" :
//                        """
//                        AND @entityId{changeIndex} NOT IN (
//                          SELECT ParentEntityId
//                          FROM [{schema}].[OutboxQueue]
//                          WHERE ParentEntityId IS NOT NULL
//                          AND QueueId=:outgoingRequestQueueId{i})
//                        """.replace("{changeIndex}", String.valueOf(changeIndex))
//                            .replace("{i}", String.valueOf(0))
//                            .replace("{schema}", schema)
//                ) +
//                """
//                END TRY
//                BEGIN CATCH
//                END CATCH
//                SET XACT_ABORT ON
//                """;
//      }
//    }

//    sql += incomingResponse == null ? "" :
//        """
//        INSERT INTO [{schema}].[OutboxResponse] (
//          EntityId,
//          EventNumber,
//          Timestamp,
//          Data,
//          RequestId
//        ) VALUES (
//          @entityId{changeIndex},
//          :incomingResponseEventNumber,
//          :timestamp,
//          :incomingResponseData,
//          :incomingResponseRequestId
//        );
//        """.replace("{changeIndex}", String.valueOf(changeIndex))
//            .replace("{schema}", schema);

    //
    // HttpOutbox incoming response event (again, guaranteed only)
    //
//    if (event != null &&
//        entityModel instanceof HttpOutbox.EntityModel outEntityModel &&
//        event.type() == HttpOutbox.Response &&
//        outEntityModel.guaranteed()
//    ) {
//      sql += //incomingResponse != null && incomingResponse.guaranteed() ?
//          """
//          DELETE [{schema}].[OutboxQueueProcessing] FROM [{schema}].[OutboxQueueProcessing] WITH (INDEX(pkOutboxQueueProcessing))
//          WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId
//          IF @@ROWCOUNT != 1
//            THROW 50004, 'Failed to delete queue processing element (change index {changeIndex}, incomingResponseRequestId)', 1;
//          """.replace("{changeIndex}", String.valueOf(changeIndex))
//              .replace("{schema}", schema);
//    }

    if (withDeadline && event != null)
      sql +=
          """
          INSERT INTO [{schema}].[Timeout] (
            EntityId,
            EntityModelId,
            EventNumber,
            Deadline,
            CorrelationId,
            Attempt
          ) VALUES (
            @entityId{changeIndex},
            :entityModelId,
            :eventNumber,
            :deadline,
            :correlationId,
            0
          );
          """.replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{schema}", schema);
    // Remove comments
    sql = sql.lines().map(line -> line.replaceAll("--.*", "")).collect(joining("\n")) + "\n";
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

//  private EntityModel childEntity(HttpOutbox.EntityModel thisModel) {
//    return entityModels.stream()
//        .filter(e -> e instanceof HttpOutbox.EntityModel outE && thisModel.equals(outE.parentEntity()))
//        .findFirst()
//        .orElse(null);
//  }

  private Function<PrimaryKeyConstraintViolation, Throwable> primaryKeyConstraintViolationMapper(List<Change> changes) {
    return pkViolation -> {
      if (pkViolation.tableName().equals("Event")) {
        try {
          EntityId entityId = new EntityId.UUID(UUID.fromString(pkViolation.duplicateKey().split(",")[0]));
          int eventNumber = Integer.parseInt(pkViolation.duplicateKey().split(",")[1].trim());
          return new EventAlreadyExists(
              entityId,
              eventNumber,
              changes
          );
        } catch (Exception e) {
          return new EventAlreadyExists(pkViolation.duplicateKey());
        }
      } else {
        // Assume id table
        return changes.stream()
            .filter(change -> !change.newSecondaryIds().isEmpty())
            .filter(change -> pkViolation.tableName().equals(new SchemaNames(schema, change.entityModel()).idTableName(change.newSecondaryIds().getFirst().model())))
            .map(change -> new RuntimeException(String.format(
                "There's already a secondary id of type %s for entity %s/%s",
                change.newSecondaryIds().getFirst().model().name(),
                change.entityModel().name(),
                change.entityId()
            )))
            .findFirst()
            .orElseThrow();
      }
    };
  }

  private Function<UniqueIndexConstraintViolation, Throwable> uniqueIndexConstraintViolationMapper(List<Change> changes) {
    return indexViolation -> changes.stream()
        .flatMap(change -> change.newSecondaryIds().stream().map(secondaryId -> tuple(change, secondaryId)))
        .filter(tuple -> indexViolation.tableName()
            .equals(new SchemaNames(schema, tuple.t1().entityModel()).idTableName(tuple.t2().model())))
        // TODO: Wrong SecondaryId can be picked here if several of the same type (hence table) are created in the same
        //       transaction. We only check for first table match.
        //       => The duplicateKey needs to be parsed and compared, too. Perhaps use the indexName as well.
        //          Ex: indexName="ixBaxNumber_SessionNumber"
        //              duplicateKey="111400, 2"
        //              tableName="Session_SessionId"
        //          Ergo: We can find SecondaryIdModel by tableName. The specific SecondaryId can be found by duplicateKey
        //          See model.columns:
        //
        //    public List<Column> columns() {
        //      return List.of(
        //          new Column("BaxNumber", "VARCHAR(11)", e -> ((SessionId)e).baxNumber()),
        //          new Column("SessionNumber", "SMALLINT", e -> ((SessionId)e).sessionNumber())
        //      );
        //    }

        .map(tuple -> new SecondaryIdAlreadyExists(
            tuple.t1(),
            tuple.t2(),
            indexViolation.duplicateKey(),
            new SchemaNames(schema, tuple.t1().entityModel()).idTableName(tuple.t2().model())
        ))
        .findFirst().orElseThrow();
  }

}
