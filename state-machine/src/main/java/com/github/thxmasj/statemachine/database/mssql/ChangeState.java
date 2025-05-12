package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.DuplicateMessage;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.Notification.IncomingRequest;
import com.github.thxmasj.statemachine.Notification.IncomingResponse;
import com.github.thxmasj.statemachine.Notification.OutgoingRequest;
import com.github.thxmasj.statemachine.Notification.OutgoingResponse;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.TraversableState;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.DataIntegrityViolation;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.statemachine.database.Client.Query.Builder;
import com.github.thxmasj.statemachine.database.Client.UniqueIndexConstraintViolation;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.IntStream;
import reactor.core.publisher.Flux;

@SuppressWarnings({"TrailingWhitespacesInTextBlock", "StringConcatenationInLoop"})
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

  private void bind(Builder spec, Change change) {
    List<Event> events = change.newEvents();
    ZonedDateTime deadline = change.deadline();
    String correlationId = change.correlationId();
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId().value());
    for (int i = 0; i < events.size(); i++) {
      var event = events.get(i);
      spec.bind("eventNumber"+i, event.getEventNumber())
          .bind("type"+i, event.getType().id())
          .bind("timestamp"+i, event.getTimestamp().withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("messageId"+i, event.getMessageId())
          .bind("clientId"+i, event.getClientId())
          .bind("data"+i, event.getMarshalledData());
    }
    for (var secondaryId : change.newSecondaryIds()) {
      for (int i = 0; i < secondaryId.model().columns().size(); i++) {
        spec.bind("secondaryId" + secondaryId.model().name() + i, secondaryId.model().columns().get(i).value().apply(secondaryId.data()));
      }
      if (secondaryId.model().isSerial())
        spec.bind("secondaryId" + secondaryId.model().name() + "SerialNumber", secondaryId.serialNumber());
    }
    for (int i = 0; i < change.incomingRequests().size(); i++) {
      IncomingRequest irq = change.incomingRequests().get(i);
      spec.bind("incomingRequestEventNumber"+i, irq.eventNumber())
          .bind("incomingRequestMessageId"+i, irq.messageId())
          .bind("incomingRequestClientId"+i, irq.clientId())
          .bind("incomingRequestDigest"+i, irq.digest())
          .bind("incomingRequestData"+i, irq.message());
    }
    for (int i = 0; i < change.outgoingRequests().size(); i++) {
      OutgoingRequest orq = change.outgoingRequests().get(i);
      if (orq.parentEntity() != null) {
        spec.bind("outgoingRequestParentEntityId"+i, orq.parentEntity().value());
      }
      spec.bind("outgoingRequestEventNumber"+i, orq.eventNumber())
          .bind("correlationId", correlationId)
          .bind("outgoingRequestGuaranteedDelivery" + i, orq.guaranteed())
          .bind("outgoingRequestData"+i, orq.message());
    }
    for (int i = 0; i < change.outgoingResponses().size(); i++) {
      OutgoingResponse ors = change.outgoingResponses().get(i);
      spec.bind("outgoingResponseEventNumber"+i, ors.eventNumber())
          .bind("outgoingResponseRequestEventNumber"+i, ors.requestEventNumber())
          .bind("outgoingResponseData"+i, ors.message());
    }
    for (int i = 0; i < change.incomingResponses().size(); i++) {
      IncomingResponse irs = change.incomingResponses().get(i);
      spec.bind("incomingResponseEventNumber"+i, irs.eventNumber())
          .bind("incomingResponseRequestId"+i, irs.requestId())
          .bind("incomingResponseData"+i, irs.message());
    }
    if (deadline != null) {
      spec.bind("eventNumber", events.getLast().getEventNumber()) // new current event number
          .bind("deadline", deadline.withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("correlationId", correlationId);
    }
  }

  public record Change(
      EntityModel entityModel,
      EntityId entityId,
      List<SecondaryId> secondaryIds,
      TraversableState sourceState,
      TraversableState targetState,
      List<Event> newEvents,
      List<SecondaryId> newSecondaryIds,
      List<IncomingRequest> incomingRequests,
      List<OutgoingResponse> outgoingResponses,
      List<OutgoingRequest> outgoingRequests,
      List<IncomingResponse> incomingResponses,
      ZonedDateTime deadline,
      String correlationId
  ) {}

  public record OutboxElement(int changeIndex, int notificationIndex, long outboxElementId, byte[] elementId) {}

  public Flux<OutboxElement> execute(List<Change> changes) {
    String sql =
        """
        SET XACT_ABORT ON;
        BEGIN TRANSACTION;
        DECLARE @OutboxElement         TABLE (ChangeIndex TINYINT, NotificationIndex TINYINT, Id BIGINT);
        DECLARE @QueueElement          TABLE (ChangeIndex TINYINT, NotificationIndex TINYINT, OutboxElementId BIGINT, ElementId BINARY(8), Guaranteed BIT);
        DECLARE @QueueElementToProcess TABLE (ChangeIndex TINYINT, NotificationIndex TINYINT, OutboxElementId BIGINT, ElementId BINARY(8));
        """ + IntStream.range(0, changes.size()).mapToObj(i -> insertSql(
            i,
            "p" + i + "_",
            changes.get(i).entityModel(),
            changes.get(i).newEvents.size(),
            changes.get(i).newSecondaryIds(),
            changes.get(i).incomingRequests(),
            changes.get(i).outgoingResponses(),
            changes.get(i).outgoingRequests(),
            changes.get(i).incomingResponses(),
            changes.get(i).deadline != null
        )).collect(joining("\n")) +
        """
        SELECT ChangeIndex, NotificationIndex, OutboxElementId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, NotificationIndex ASC
        COMMIT TRANSACTION;
        """;
    Builder spec = databaseClient.sql(sql).name("ChangeState");
    for (int i = 0; i < changes.size(); i++)
      bind(spec.parameterPrefix("p" + i + "_"), changes.get(i));
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("NotificationIndex", Integer.class),
            row.get("OutboxElementId", Long.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(t -> isDuplicateMessage(t, changes), DuplicateMessage::new)
        .onErrorMap(DataIntegrityViolation.class, t -> {
          Change change = isRace(t, changes);
          if (change != null)
            return new ChangeRaced(change, t.tableName());
          else
            return t;
        });
  }

  private String insertSql(
      int changeIndex,
      String parameterPrefix,
      EntityModel entityModel,
      int numberOfEvents,
      List<SecondaryId> secondaryIds,
      List<IncomingRequest> incomingRequests,
      List<OutgoingResponse> outgoingResponses,
      List<OutgoingRequest> outgoingRequests,
      List<IncomingResponse> incomingResponses,
      boolean withDeadline
  ) {
    var names = new SchemaNames(schema, entityModel);
    var q = names.qualifiedNames();
    String sql =
        """
        DECLARE @entityId{changeIndex} UNIQUEIDENTIFIER
        SELECT @entityId{changeIndex}=:entityId
        """.replace("{changeIndex}", String.valueOf(changeIndex));
    for (var secondaryId : secondaryIds) {
      sql +=
          """
          INSERT INTO {idTable} (EntityId, {columnList})
          VALUES (@entityId{changeIndex}, {valueList})
          """.replace("{idTable}", q.idTable(secondaryId.model()))
              .replace("{columnList}",
                  secondaryId.model().columns().stream().map(SchemaNames.Column::name).collect(joining(",")) + (
                      secondaryId.model().isSerial() ? ",SerialNumber" : "")
              )
              .replace("{changeIndex}", String.valueOf(changeIndex))
              .replace("{valueList}",
                  range(0, secondaryId.model().columns().size()).mapToObj(i -> ":secondaryId" + secondaryId.model()
                      .name() + i).collect(joining(",")) + (secondaryId.model().isSerial() ? ",:secondaryId"
                      + secondaryId.model().name() + "SerialNumber" : "")
              );
    }

    sql +=
        """
        DELETE {timeoutTable} FROM {timeoutTable} WITH (INDEX([ixEntityId]))
        WHERE EntityId=@entityId{changeIndex} AND EventNumber=:eventNumber0 - 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{timeoutTable}", q.timeoutTable());

    sql +=
        """
        INSERT INTO {eventTable} (
          EntityId,
          EventNumber,
          Type,
          Timestamp,
          MessageId,
          ClientId,
          Data
        ) VALUES
        """.replace("{eventTable}", q.eventTable());
    sql += range(0, numberOfEvents).mapToObj(i ->
        """
        (@entityId{changeIndex},:eventNumber{i},:type{i},:timestamp{i},:messageId{i},:clientId{i},:data{i})
        """.replace("{changeIndex}", String.valueOf(changeIndex)).replace("{i}", String.valueOf(i))
    ).collect(joining(","));

    sql += range(0, incomingRequests.size()).mapToObj(i ->
        """
        INSERT INTO {inboxTable} (
          EntityId,
          EventNumber,
          Timestamp,
          MessageId,
          ClientId,
          Digest,
          Data
        ) VALUES (
          @entityId{changeIndex},
          :incomingRequestEventNumber{i},
          :timestamp0,
          :incomingRequestMessageId{i},
          :incomingRequestClientId{i},
          :incomingRequestDigest{i},
          :incomingRequestData{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{inboxTable}", q.inboxTable())
    ).collect(joining());

    sql += range(0, incomingResponses.size()).mapToObj(i ->
        /* Deletes corresponding outgoing request from queue when incoming response has arrived. Note that this must
           be done prior to insertion of new outgoing requests (see below), otherwise, in case of rollback, the new
           outgoing request will be deleted as well */
        """
        DELETE {queueTable} FROM {queueTable} WITH (INDEX([ixEntityId]))
        WHERE EntityId=@entityId{changeIndex} AND OutboxElementId=:incomingResponseRequestId{i}
        IF @@ROWCOUNT != 1
          THROW 50003, 'Failed to delete outgoing request from queue', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{queueTable}", q.queueTable(incomingResponses.get(i).subscriber()))
    ).collect(joining());

    sql += range(0, outgoingRequests.size()).mapToObj(i ->
        """
        INSERT INTO {outboxTable} (
          EntityId,
          EventNumber,
          Timestamp,
          Data
        )
        OUTPUT {changeIndex}, {i}, inserted.Id INTO @OutboxElement
        VALUES (
          @entityId{changeIndex},
          :outgoingRequestEventNumber{i},
          :timestamp0,
          :outgoingRequestData{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{outboxTable}", q.outboxTable(outgoingRequests.get(i).subscriber()))
    ).collect(joining());

    sql += range(0, outgoingRequests.size()).mapToObj(i ->
        """
        INSERT INTO {queueTable} (
          EntityId,
          {parentEntityColumn}
          EventNumber,
          Guaranteed,
          CorrelationId,
          Timestamp,
          OutboxElementId
        )
        OUTPUT {changeIndex}, {i}, inserted.OutboxElementId, inserted.ElementId, inserted.Guaranteed INTO @QueueElement
        VALUES (
          @entityId{changeIndex},
          {parentEntityValue}
          :outgoingRequestEventNumber{i},
          :outgoingRequestGuaranteedDelivery{i},
          :correlationId,
          :timestamp0,
          (SELECT Id FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND NotificationIndex={i})
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{queueTable}", q.queueTable(outgoingRequests.get(i).subscriber()))
            .replace("{parentEntityColumn}", outgoingRequests.get(i).parentEntity() != null ? "ParentEntityId," : "")
            .replace("{parentEntityValue}", outgoingRequests.get(i).parentEntity() != null ? ":outgoingRequestParentEntityId" + i + "," : "")
    ).collect(joining());

    sql +=
        """
        INSERT INTO @QueueElementToProcess (ChangeIndex, NotificationIndex, OutboxElementId, ElementId)
        SELECT ChangeIndex, NotificationIndex, OutboxElementId, ElementId FROM @QueueElement
        WHERE Guaranteed = 0;
        """;

    sql += range(0, outgoingRequests.size()).filter(i -> outgoingRequests.get(i).guaranteed()).mapToObj(i ->
        """
        SET XACT_ABORT OFF
        BEGIN TRY
        INSERT INTO {processingTable} (
          ElementId,
          EntityId,
          EventNumber,
          Guaranteed,
          Data,
          CorrelationId,
          EnqueuedAt,
          Attempt,
          NextAttemptAt,
          OutboxElementId
        )
        OUTPUT {changeIndex}, {i}, inserted.OutboxElementId, inserted.ElementId INTO @QueueElementToProcess
        SELECT
          (SELECT ElementId FROM @QueueElement WHERE ChangeIndex = {changeIndex} AND NotificationIndex = {i}),
          @entityId{changeIndex},
          :outgoingRequestEventNumber{i},
          :outgoingRequestGuaranteedDelivery{i},
          :outgoingRequestData{i},
          :correlationId,
          :timestamp0,
          1,
          (DATEADD(millisecond, 10*1000, :timestamp0)), -- TODO: (DATEADD(millisecond, :minimumBackoff*1000, :now))
          (SELECT Id FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND NotificationIndex={i})
        WHERE @entityId{changeIndex} NOT IN (
          SELECT EntityId
           FROM {dlqTable}
             WITH (INDEX([{dlqTablePK}]))
          )
          AND @entityId{changeIndex} NOT IN (
            SELECT EntityId
            FROM {processingTable}
            WITH (INDEX([{processingTablePK}]))
          )
          """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{processingTable}", q.processingTable(outgoingRequests.get(i).subscriber()))
            .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(outgoingRequests.get(i).subscriber()))
            .replace("{dlqTable}", q.dlqTable(outgoingRequests.get(i).subscriber()))
            .replace("{dlqTablePK}", names.dlqTablePrimaryKeyName(outgoingRequests.get(i).subscriber())) +
            (
                childEntity(entityModel) == null ? "" :
                    """
                    AND @entityId{changeIndex} NOT IN (
                      SELECT ParentEntityId
                      FROM {childQueueTable}
                      WHERE ParentEntityId IS NOT NULL)
                    """.replace("{changeIndex}", String.valueOf(changeIndex))
                        .replace("{childQueueTable}", new SchemaNames(schema, childEntity(entityModel)).qualifiedNames().queueTable(outgoingRequests.get(i).subscriber()))
            ) +
        """
        END TRY
        BEGIN CATCH
        END CATCH
        SET XACT_ABORT ON
        """
    ).collect(joining());

    sql += range(0, incomingResponses.size()).mapToObj(i ->
        """
        INSERT INTO {inboxTable} (
          EntityId,
          EventNumber,
          Timestamp,
          Data,
          RequestId
        ) VALUES (
          @entityId{changeIndex},
          :incomingResponseEventNumber{i},
          :timestamp0,
          :incomingResponseData{i},
          :incomingResponseRequestId{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{inboxTable}", q.inboxTable(incomingResponses.get(i).subscriber()))
    ).collect(joining());

    sql += range(0, outgoingResponses.size()).mapToObj(i ->
        """
        INSERT INTO {outboxTable} (
          EntityId,
          EventNumber,
          Timestamp,
          Data,
          InboxEventNumber
        ) VALUES (
          @entityId{changeIndex},
          :outgoingResponseEventNumber{i},
          :timestamp0,
          :outgoingResponseData{i},
          :outgoingResponseRequestEventNumber{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{outboxTable}", q.outboxTable())
    ).collect(joining());

    sql += range(0, incomingResponses.size()).filter(i -> incomingResponses.get(i).guaranteed()).mapToObj(i ->
        """
        DELETE {processingTable} FROM {processingTable} WITH (INDEX({processingTablePK}))
        WHERE EntityId=@entityId{changeIndex} AND OutboxElementId=:incomingResponseRequestId{i}
        IF @@ROWCOUNT != 1
          THROW 50004, 'Failed to delete queue processing element', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{processingTable}", q.processingTable(incomingResponses.get(i).subscriber()))
            .replace("{processingTablePK}", names.notificationQueueProcessingTablePrimaryKeyName(incomingResponses.get(i).subscriber()))
    ).collect(joining());

    if (withDeadline) sql +=
        """
        INSERT INTO {timeoutTable} (
          EntityId,
          EventNumber,
          Deadline,
          CorrelationId,
          Attempt
        ) VALUES (
          @entityId{changeIndex},
          :eventNumber,
          :deadline,
          :correlationId,
          0
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{timeoutTable}", q.timeoutTable());
    // Remove comments
    sql = sql.lines().map(line -> line.replaceAll("--.*", "")).collect(joining("\n")) + "\n";
    return sql.replaceAll(":([a-zA-Z0-9_]+)", ":" + parameterPrefix + "$1");
  }

  private boolean isDuplicateMessage(Throwable e, List<Change> changes) {
    if (e instanceof UniqueIndexConstraintViolation f) {
      return changes.stream().anyMatch(change -> new SchemaNames(schema, change.entityModel()).inboxTableName().equals(f.tableName()) && f.indexName().equals("ixMessageId_ClientId"));
    }
    return false;
  }

  private EntityModel childEntity(EntityModel thisModel) {
    return entityModels.stream()
        .filter(e -> thisModel.equals(e.parentEntity()))
        .findFirst()
        .orElse(null);
  }

  private Change isRace(Throwable e, List<Change> changes) {
    if (e instanceof PrimaryKeyConstraintViolation pkViolation) {
      return changes.stream()
          .filter(change ->
              pkViolation.tableName().equals(new SchemaNames(schema, change.entityModel()).eventTableName()) ||
                  change.entityModel.secondaryIds().stream().map(id -> new SchemaNames(schema, change.entityModel).idTableName(id))
                      .anyMatch(idTableName -> pkViolation.tableName().equals(idTableName))
          )
          .findFirst().orElseThrow();
    } else if (e instanceof UniqueIndexConstraintViolation ixViolation) {
      return changes.stream()
          .filter(change -> change.newSecondaryIds().stream().map(id -> new SchemaNames(schema, change.entityModel()).idTableName(id.model())).anyMatch(idTableName -> idTableName.equals(ixViolation.tableName())))
          .findFirst().orElseThrow();
    }
    return null;
  }

}
