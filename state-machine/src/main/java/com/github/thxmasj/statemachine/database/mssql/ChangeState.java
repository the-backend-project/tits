package com.github.thxmasj.statemachine.database.mssql;

import static java.util.stream.Collectors.joining;
import static java.util.stream.IntStream.range;

import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.message.Message.IncomingRequest;
import com.github.thxmasj.statemachine.message.Message.IncomingResponse;
import com.github.thxmasj.statemachine.message.Message.OutgoingRequest;
import com.github.thxmasj.statemachine.message.Message.OutgoingResponse;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.TraversableState;
import com.github.thxmasj.statemachine.database.ChangeRaced;
import com.github.thxmasj.statemachine.database.Client;
import com.github.thxmasj.statemachine.database.Client.DataIntegrityViolation;
import com.github.thxmasj.statemachine.database.Client.PrimaryKeyConstraintViolation;
import com.github.thxmasj.statemachine.database.Client.Query.Builder;
import com.github.thxmasj.statemachine.database.Client.UniqueIndexConstraintViolation;
import com.github.thxmasj.statemachine.database.DuplicateMessage;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
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

  private void bind(Builder spec, Change change) {
    List<Event<?>> events = change.newEvents();
    ZonedDateTime deadline = change.deadline();
    String correlationId = change.correlationId();
    if (change.entityId() != null)
      spec.bind("entityId", change.entityId().value());
    spec.bind("entityModelId", change.entityModel().id());
    for (int i = 0; i < events.size(); i++) {
      var event = events.get(i);
      spec.bind("eventNumber"+i, event.eventNumber())
          .bind("type"+i, event.type().id())
          .bind("timestamp"+i, event.timestamp().withZoneSameInstant(clock.getZone()).toLocalDateTime())
          .bind("messageId"+i, event.messageId())
          .bind("clientId"+i, event.clientId())
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
      spec.bind("incomingRequestId"+i, irq.id())
          .bind("incomingRequestEventNumber"+i, irq.eventNumber())
          .bind("incomingRequestMessageId"+i, irq.messageId())
          .bind("incomingRequestClientId"+i, irq.clientId())
          .bind("incomingRequestDigest"+i, irq.digest())
          .bind("incomingRequestData"+i, irq.message().message());
    }
    for (int i = 0; i < change.outgoingRequests().size(); i++) {
      OutgoingRequest orq = change.outgoingRequests().get(i);
      if (orq.parentEntity() != null) {
        spec.bind("outgoingRequestParentEntityId"+i, orq.parentEntity().value());
      }
      spec.bind("outgoingRequestId" + i, orq.id())
          .bind("outgoingRequestQueueId" + i, orq.queue().id())
          .bind("outgoingRequestEventNumber"+i, orq.eventNumber())
          .bind("outgoingRequestCreatorId"+i, orq.creatorId())
          .bind("correlationId", correlationId)
          .bind("outgoingRequestGuaranteedDelivery" + i, orq.guaranteed())
          .bind("outgoingRequestData"+i, orq.message().message());
    }
    for (int i = 0; i < change.outgoingResponses().size(); i++) {
      OutgoingResponse ors = change.outgoingResponses().get(i);
      spec.bind("outgoingResponseEventNumber"+i, ors.eventNumber())
          .bind("outgoingResponseRequestId"+i, ors.requestId())
          .bind("outgoingResponseData"+i, ors.message().message());
    }
    for (int i = 0; i < change.incomingResponses().size(); i++) {
      IncomingResponse irs = change.incomingResponses().get(i);
      spec.bind("incomingResponseEventNumber"+i, irs.eventNumber())
          .bind("incomingResponseRequestId"+i, irs.requestId())
          .bind("incomingResponseData"+i, irs.message().message());
    }
    if (deadline != null) {
      spec.bind("eventNumber", events.getLast().eventNumber()) // new current event number
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
      List<Event<?>> newEvents,
      List<SecondaryId> newSecondaryIds,
      List<IncomingRequest> incomingRequests,
      List<OutgoingResponse> outgoingResponses,
      List<OutgoingRequest> outgoingRequests,
      List<IncomingResponse> incomingResponses,
      ZonedDateTime deadline,
      String correlationId
  ) {}

  public record OutboxElement(int changeIndex, int messageIndex, UUID requestId, byte[] elementId) {}

  public Flux<OutboxElement> execute(List<Change> changes) {
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
            changes.get(i).newEvents.size(),
            changes.get(i).newSecondaryIds(),
            changes.get(i).incomingRequests(),
            changes.get(i).outgoingResponses(),
            changes.get(i).outgoingRequests(),
            changes.get(i).incomingResponses(),
            changes.get(i).deadline != null
        )).collect(joining("\n")) +
        """
        SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElementToProcess ORDER BY ChangeIndex ASC, MessageIndex ASC
        COMMIT TRANSACTION;
        """;
    Builder spec = databaseClient.sql(sql).name("ChangeState");
    for (int i = 0; i < changes.size(); i++)
      bind(spec.parameterPrefix("p" + i + "_"), changes.get(i));
    return spec.map(row -> new OutboxElement(
            row.get("ChangeIndex", Integer.class),
            row.get("MessageIndex", Integer.class),
            row.get("RequestId", UUID.class),
            row.get("ElementId", byte[].class)
        ))
        .all()
        .onErrorMap(this::isDuplicateMessage, DuplicateMessage::new)
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
        DELETE [{schema}].[Timeout] FROM [{schema}].[Timeout] WITH (INDEX([ixEntityId]))
        WHERE EntityId=@entityId{changeIndex} AND EventNumber=:eventNumber0 - 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{schema}", schema);

    sql +=
        """
        INSERT INTO [{schema}].[Event] (
          EntityId,
          EventNumber,
          Type,
          Timestamp,
          MessageId,
          ClientId,
          Data
        ) VALUES
        """.replace("{schema}", schema);
    sql += range(0, numberOfEvents).mapToObj(i ->
        """
        (@entityId{changeIndex},:eventNumber{i},:type{i},:timestamp{i},:messageId{i},:clientId{i},:data{i})
        """.replace("{changeIndex}", String.valueOf(changeIndex)).replace("{i}", String.valueOf(i))
    ).collect(joining(","));

    sql += range(0, incomingRequests.size()).mapToObj(i ->
        """
        INSERT INTO [{schema}].[InboxRequest] (
          Id,
          EntityId,
          EventNumber,
          Timestamp,
          MessageId,
          ClientId,
          Digest,
          Data
        ) VALUES (
          :incomingRequestId{i},
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
            .replace("{schema}", schema)
    ).collect(joining());

    sql += range(0, incomingResponses.size()).mapToObj(i ->
        /* Deletes corresponding outgoing request from queue when incoming response has arrived. Note that this must
           be done prior to insertion of new outgoing requests (see below), otherwise, in case of rollback, the new
           outgoing request will be deleted as well */
        """
        DELETE [{schema}].[OutboxQueue] FROM [{schema}].[OutboxQueue] WITH (INDEX([ixEntityId]))
        WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId{i}
        IF @@ROWCOUNT != 1
          THROW 50003, 'Failed to delete outgoing request from queue', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
    ).collect(joining());

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
          :timestamp0,
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
          :timestamp0,
          (SELECT RequestId FROM @OutboxElement WHERE ChangeIndex={changeIndex} AND MessageIndex={i})
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
            .replace("{parentEntityColumn}", outgoingRequests.get(i).parentEntity() != null ? "ParentEntityId," : "")
            .replace("{parentEntityValue}", outgoingRequests.get(i).parentEntity() != null ? ":outgoingRequestParentEntityId" + i + "," : "")
    ).collect(joining());

    sql +=
        """
        INSERT INTO @QueueElementToProcess (ChangeIndex, MessageIndex, RequestId, ElementId)
        SELECT ChangeIndex, MessageIndex, RequestId, ElementId FROM @QueueElement
        WHERE Guaranteed = 0;
        """;

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
          :timestamp0,
          1,
          (DATEADD(millisecond, 10*1000, :timestamp0)), -- TODO: (DATEADD(millisecond, :minimumBackoff*1000, :now))
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

    sql += range(0, incomingResponses.size()).mapToObj(i ->
        """
        INSERT INTO [{schema}].[OutboxResponse] (
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
            .replace("{schema}", schema)
    ).collect(joining());

    sql += range(0, outgoingResponses.size()).mapToObj(i ->
        """
        INSERT INTO [{schema}].[InboxResponse] (
          EntityId,
          EventNumber,
          Timestamp,
          Data,
          RequestId
        ) VALUES (
          @entityId{changeIndex},
          :outgoingResponseEventNumber{i},
          :timestamp0,
          :outgoingResponseData{i},
          :outgoingResponseRequestId{i}
        );
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
    ).collect(joining());

    sql += range(0, incomingResponses.size()).filter(i -> incomingResponses.get(i).guaranteed()).mapToObj(i ->
        """
        DELETE [{schema}].[OutboxQueueProcessing] FROM [{schema}].[OutboxQueueProcessing] WITH (INDEX(pkOutboxQueueProcessing))
        WHERE EntityId=@entityId{changeIndex} AND RequestId=:incomingResponseRequestId{i}
        IF @@ROWCOUNT != 1
          THROW 50004, 'Failed to delete queue processing element (change index {changeIndex}, incomingResponseRequestId{i})', 1;
        """.replace("{changeIndex}", String.valueOf(changeIndex))
            .replace("{i}", String.valueOf(i))
            .replace("{schema}", schema)
    ).collect(joining());

    if (withDeadline) sql +=
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

  private boolean isDuplicateMessage(Throwable e) {
    if (e instanceof UniqueIndexConstraintViolation f) {
      return "InboxRequest".equals(f.tableName()) && f.indexName().equals("ixMessageId_ClientId");
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
              pkViolation.tableName().equals("Event") ||
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
