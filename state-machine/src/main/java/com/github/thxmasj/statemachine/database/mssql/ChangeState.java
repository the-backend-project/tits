package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.Tuples.tuple;
import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.statemachine.database.Client.Query.Builder;
import com.github.thxmasj.statemachine.database.Client.UniqueIndexConstraintViolation;
import com.github.thxmasj.statemachine.database.EventAlreadyExists;
import com.github.thxmasj.statemachine.database.SecondaryIdAlreadyExists;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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

  private void bind(Builder spec, ZonedDateTime timestamp, Change change) {
    Event<?> event = change.newEvent();
    ZonedDateTime deadline = change.deadline();
    String correlationId = change.correlationId();
    if (change.eventLog().entityId() != null)
      spec.bind("entityId", change.eventLog().entityId().value());
    spec.bind("entityModelId", change.eventLog().entityModel().id());
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
//    if (change.incomingRequest() != null) {
//      IncomingRequest irq = change.incomingRequest();
//      spec.bind("incomingRequestId", irq.id())
//          .bind("incomingRequestEventNumber", irq.eventNumber())
//          .bind("incomingRequestMessageId", irq.messageId())
//          .bind("incomingRequestClientId", irq.clientId())
//          .bind("incomingRequestDigest", irq.digest())
//          .bind("incomingRequestData", irq.message().message());
//    }
    for (int i = 0; i < change.outgoingRequests().size(); i++) {
      OutgoingRequest orq = change.outgoingRequests().get(i);
      if (orq.parentEntity() != null) {
        spec.bind("outgoingRequestParentEntityId" + i, orq.parentEntity().value());
      }
      spec.bind("outgoingRequestId" + i, orq.id())
          .bind("outgoingRequestQueueId" + i, orq.queue().id())
          .bind("outgoingRequestEventNumber" + i, orq.eventNumber())
          .bind("outgoingRequestCreatorId" + i, orq.creatorId())
          .bind("correlationId", correlationId)
          .bind("outgoingRequestGuaranteedDelivery" + i, orq.guaranteed())
          .bind("outgoingRequestData" + i, orq.message().message());
    }
//    if (change.outgoingResponse() != null) {
//      OutgoingResponse ors = change.outgoingResponse();
//      spec.bind("outgoingResponseEventNumber", ors.eventNumber())
//          .bind("outgoingResponseRequestId", ors.requestId())
//          .bind("outgoingResponseData", ors.message().message());
//    }
    if (change.incomingResponse() != null) {
      IncomingResponse irs = change.incomingResponse();
      spec.bind("incomingResponseEventNumber", irs.eventNumber())
          .bind("incomingResponseRequestId", irs.requestId())
          .bind("incomingResponseData", irs.message().message());
    }
    if (deadline != null) {
      spec//.bind("eventNumber", events.getLast().eventNumber()) // new current event number
          .bind("deadline", deadline.withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  public interface Change {

    EventLog eventLog();

    boolean storeEvent();

    Event<?> newEvent();

    State toState();

    List<SecondaryId<?>> newSecondaryIds();

    List<OutgoingRequest> outgoingRequests();

    IncomingResponse incomingResponse();

    ZonedDateTime deadline();

    String correlationId();
  }

  public record OutboxElement(int changeIndex, int messageIndex, UUID requestId, byte[] elementId) {}

  public Flux<OutboxElement> execute(ZonedDateTime timestamp, List<Change> changes) {
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
            changes.get(i).eventLog().entityModel(),
            changes.get(i).newEvent() != null && changes.get(i).storeEvent(),
            changes.get(i).newSecondaryIds(),
            //changes.get(i).incomingRequest(),
            //changes.get(i).outgoingResponse(),
            changes.get(i).outgoingRequests(),
            changes.get(i).incomingResponse(),
            changes.get(i).deadline() != null
        )).collect(joining("\n")) +
            """
            INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
            WHERE Guaranteed = 0;
            
            SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
            COMMIT TRANSACTION;
            """;
    Builder spec = databaseClient.sql(sql).name("ChangeState");
    for (int i = 0; i < changes.size(); i++) {bind(spec.parameterPrefix("p" + i + "_"), timestamp, changes.get(i));}
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("MessageIndex", Integer.class),
            row.get("RequestId", UUID.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(PrimaryKeyConstraintViolation.class, primaryKeyConstraintViolationMapper(changes))
        .onErrorMap(UniqueIndexConstraintViolation.class, uniqueIndexConstraintViolationMapper(changes))
        //.onErrorMap(this::isDuplicateMessage, t -> new DuplicateMessage(t))
//        .onErrorMap(DataIntegrityViolation.class, t -> {
//          Change change = isRace(t, changes);
//          if (change != null)
//            return new ChangeRaced(change, t.tableName());
//          else
//            return t;
//        });
        ;
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      EntityModel entityModel,
      boolean withEvent,
      List<SecondaryId<?>> secondaryIds,
      //IncomingRequest incomingRequest,
      //OutgoingResponse outgoingResponse,
      List<OutgoingRequest> outgoingRequests,
      IncomingResponse incomingResponse,
      boolean withDeadline
  ) {
    var names = new SchemaNames(schema, entityModel);
    var q = names.qualifiedNames();
    String sql =
        """
        DECLARE @entityId{changeIndex} UNIQUEIDENTIFIER
        SELECT @entityId{changeIndex}=:entityId
        """.replace("{changeIndex}", String.valueOf(changeIndex));
    for (SecondaryId<?> secondaryId : secondaryIds) {
      sql +=
          """
          INSERT INTO {idTable} (EntityId, {columnList})
          VALUES (@entityId{changeIndex}, {valueList})
          """.replace("{idTable}", q.idTable(secondaryId.model()))
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
              );
    }

    if (withEvent) {
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

//    sql += incomingRequest == null ? "" :
//        """
//        INSERT INTO [{schema}].[InboxRequest] (
//          Id,
//          EntityId,
//          EventNumber,
//          Timestamp,
//          MessageId,
//          ClientId,
//          Digest,
//          Data
//        ) VALUES (
//          :incomingRequestId,
//          @entityId{changeIndex},
//          :incomingRequestEventNumber,
//          :timestamp,
//          :incomingRequestMessageId,
//          :incomingRequestClientId,
//          :incomingRequestDigest,
//          :incomingRequestData
//        );
//        """.replace("{changeIndex}", String.valueOf(changeIndex))
//            .replace("{schema}", schema);

    sql += incomingResponse == null ? "" :
        /* Deletes corresponding outgoing request from queue when incoming response has arrived. Note that this must
           be done prior to insertion of new outgoing requests (see below), otherwise, in case of rollback, the new
           outgoing request will be deleted as well */
        """
        DELETE [{schema}].[OutboxQueue] FROM [{schema}].[OutboxQueue] WITH (INDEX([ixEntityId]))
        WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId
        IF @@ROWCOUNT != 1
          THROW 50003, 'Failed to delete outgoing request from queue', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{schema}", schema);

    sql += range(0, outgoingRequests.size()).mapToObj(i ->
        """
        INSERT INTO [{schema}].[OutboxRequest] (
          Id,
          QueueId,
          EntityId,
          EventNumber,
          Timestamp,
          Data
        )
        OUTPUT {changeIndex}, {i}, inserted.Id INTO @OutboxElement
        VALUES (
          :outgoingRequestId{i},
          :outgoingRequestQueueId{i},
          @entityId{changeIndex},
          :outgoingRequestEventNumber{i},
          :timestamp,
          :outgoingRequestData{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
    ).collect(joining());

    sql += range(0, outgoingRequests.size()).mapToObj(i ->
        """
        INSERT INTO [{schema}].[OutboxQueue] (
          QueueId,
          EntityModelId,
          EntityId,
          {parentEntityColumn}
          EventNumber,
          CreatorId,
          Guaranteed,
          CorrelationId,
          Timestamp,
          RequestId
        )
        OUTPUT {changeIndex}, {i}, inserted.RequestId, inserted.ElementId, inserted.Guaranteed INTO @QueueElement
        VALUES (
          :outgoingRequestQueueId{i},
          :entityModelId,
          @entityId{changeIndex},
          {parentEntityValue}
          :outgoingRequestEventNumber{i},
          :outgoingRequestCreatorId{i},
          :outgoingRequestGuaranteedDelivery{i},
          :correlationId,
          :timestamp,
          (SELECT RequestId FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND MessageIndex={i})
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
            .replace("{parentEntityColumn}", outgoingRequests.get(i).parentEntity() != null ? "ParentEntityId," : "")
            .replace(
                "{parentEntityValue}",
                outgoingRequests.get(i).parentEntity() != null ? ":outgoingRequestParentEntityId" + i + "," : ""
            )
    ).collect(joining());

//    sql +=
//        """
//        INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
//        SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
//        WHERE Guaranteed = 0;
//        """;

    sql += range(0, outgoingRequests.size()).filter(i -> outgoingRequests.get(i).guaranteed()).mapToObj(i ->
        """
        SET XACT_ABORT OFF
        BEGIN TRY
        INSERT INTO [{schema}].[OutboxQueueProcessing] (
          ElementId,
          QueueId,
          EntityModelId,
          EntityId,
          EventNumber,
          CreatorId,
          Guaranteed,
          Data,
          CorrelationId,
          EnqueuedAt,
          Attempt,
          NextAttemptAt,
          RequestId
        )
        OUTPUT {changeIndex}, {i}, inserted.RequestId, inserted.ElementId INTO @QueueElementToProcess
        SELECT
          (SELECT ElementId FROM @QueueElement WHERE ChangeIndex = {changeIndex} AND MessageIndex = {i}),
          :outgoingRequestQueueId{i},
          :entityModelId,
          @entityId{changeIndex},
          :outgoingRequestEventNumber{i},
          :outgoingRequestCreatorId{i},
          :outgoingRequestGuaranteedDelivery{i},
          :outgoingRequestData{i},
          :correlationId,
          :timestamp,
          1,
          (DATEADD(millisecond, 10*1000, :timestamp)), -- TODO: (DATEADD(millisecond, :minimumBackoff*1000, :now))
          (SELECT RequestId FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND MessageIndex={i})
        WHERE @entityId{changeIndex} NOT IN (
          SELECT EntityId
          FROM [{schema}].[OutboxDeadLetterQueue]
          WITH (INDEX([pkOutboxDeadLetterQueue]))
          WHERE QueueId=:outgoingRequestQueueId{i}
        )
        AND @entityId{changeIndex} NOT IN (
          SELECT EntityId
          FROM [{schema}].[OutboxQueueProcessing]
          WITH (INDEX([pkOutboxQueueProcessing]))
          WHERE QueueId=:outgoingRequestQueueId{i}
        )
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
            +
            (
                childEntity(entityModel) == null ? "" :
                    """
                    AND @entityId{changeIndex} NOT IN (
                      SELECT ParentEntityId
                      FROM [{schema}].[OutboxQueue]
                      WHERE ParentEntityId IS NOT NULL
                      AND QueueId=:outgoingRequestQueueId{i})
                    """.replace("{changeIndex}", String.valueOf(changeIndex))
                        .replace("{i}", String.valueOf(i))
                        .replace("{schema}", schema)
            ) +
            """
            END TRY
            BEGIN CATCH
            END CATCH
            SET XACT_ABORT ON
            """
    ).collect(joining());

    sql += incomingResponse == null ? "" :
        """
        INSERT INTO [{schema}].[OutboxResponse] (
          EntityId,
          EventNumber,
          Timestamp,
          Data,
          RequestId
        ) VALUES (
          @entityId{changeIndex},
          :incomingResponseEventNumber,
          :timestamp,
          :incomingResponseData,
          :incomingResponseRequestId
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{schema}", schema);

//    sql += outgoingResponse == null ? "" :
//        """
//        INSERT INTO [{schema}].[InboxResponse] (
//          EntityId,
//          EventNumber,
//          Timestamp,
//          Data,
//          RequestId
//        ) VALUES (
//          @entityId{changeIndex},
//          :outgoingResponseEventNumber,
//          :timestamp,
//          :outgoingResponseData,
//          :outgoingResponseRequestId
//        );
//        """.replace("{changeIndex}", String.valueOf(changeIndex))
//            .replace("{schema}", schema);

    sql += incomingResponse != null && incomingResponse.guaranteed() ?
        """
        DELETE [{schema}].[OutboxQueueProcessing] FROM [{schema}].[OutboxQueueProcessing] WITH (INDEX(pkOutboxQueueProcessing))
        WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId
        IF @@ROWCOUNT != 1
          THROW 50004, 'Failed to delete queue processing element (change index {changeIndex}, incomingResponseRequestId)', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{schema}", schema) : "";

    if (withDeadline && withEvent)
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

  private EntityModel childEntity(EntityModel thisModel) {
    return entityModels.stream()
        .filter(e -> thisModel.equals(e.parentEntity()))
        .findFirst()
        .orElse(null);
  }

  private Function<PrimaryKeyConstraintViolation, Throwable> primaryKeyConstraintViolationMapper(List<Change> changes) {
    return pkViolation -> {
      if (pkViolation.tableName().equals("Event")) {
        try {
          ArrayList<Event<?>> offenders = new ArrayList<>();
          EntityId entityId = new EntityId.UUID(UUID.fromString(pkViolation.duplicateKey().split(",")[0]));
          int eventNumber = Integer.parseInt(pkViolation.duplicateKey().split(",")[1].trim());
          return new EventAlreadyExists(
              entityId,
              eventNumber,
              changes.stream()
                  .filter(c -> c.newEvent() != null && c.newEvent().eventNumber().equals(eventNumber) && c.eventLog()
                      .entityId()
                      .equals(entityId))
                  .toList()
          );
        } catch (Exception e) {
          return new EventAlreadyExists(pkViolation.duplicateKey());
        }

      }
      return changes.stream()
          .map(change ->
              change.eventLog().entityModel().secondaryIds().stream()
                  .map(id -> new SchemaNames(schema, change.eventLog().entityModel()).idTableName(id))
                  .filter(idTableName -> pkViolation.tableName().equals(idTableName))
                  .map(idTableName -> new ChangeRaced(change, idTableName))
                  .findFirst().orElseThrow()
          )
          .findFirst().orElseThrow();
    };
  }

  private Function<UniqueIndexConstraintViolation, Throwable> uniqueIndexConstraintViolationMapper(List<Change> changes) {
    return indexViolation -> changes.stream()
        .flatMap(change -> change.newSecondaryIds().stream().map(secondaryId -> tuple(change, secondaryId)))
        .filter(tuple -> indexViolation.tableName()
            .equals(new SchemaNames(schema, tuple.t1().eventLog().entityModel()).idTableName(tuple.t2().model())))
        .map(tuple -> new SecondaryIdAlreadyExists(
            tuple.t1(),
            tuple.t2(),
            indexViolation.duplicateKey(),
            new SchemaNames(schema, tuple.t1().eventLog().entityModel()).idTableName(tuple.t2().model())
        ))
        .findFirst().orElseThrow();
  }

  private Change isRace(Throwable e, List<Change> changes) {
    if (e instanceof PrimaryKeyConstraintViolation pkViolation) {
      return changes.stream()
          .filter(change ->
              pkViolation.tableName().equals("Event") ||
                  change.eventLog()
                      .entityModel()
                      .secondaryIds()
                      .stream()
                      .map(id -> new SchemaNames(schema, change.eventLog().entityModel()).idTableName(id))
                      .anyMatch(idTableName -> pkViolation.tableName().equals(idTableName))
          )
          .findFirst().orElseThrow();
    } else if (e instanceof UniqueIndexConstraintViolation ixViolation) {
      return changes.stream()
          .filter(change -> change.newSecondaryIds()
              .stream()
              .map(id -> new SchemaNames(schema, change.eventLog().entityModel()).idTableName(id.model()))
              .anyMatch(idTableName -> idTableName.equals(ixViolation.tableName())))
          .findFirst().orElseThrow();
    }
    return null;
  }

}
