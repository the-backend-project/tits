package com.github.thxmasj.statemachine.database.mssql;

import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.Subscriber;
import java.sql.ResultSet;
import java.util.List;
import java.util.function.Function;

public record SchemaNames(
  String schema,
  EntityModel model
) {

  public String eventTableName() {
    return "Event";
  }

  public String eventTablePrimaryKeyName() {
    return "pkEvent";
  }

  public String indexName(List<Column> columns) {
    return String.format("ix%s", String.join("_", columns.stream().map(Column::name).toList()));
  }

  public String timeoutTableName() {
    return "Timeout";
  }

  public String notificationQueueProcessingTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s", processingTableName(subscriber));
  }

  public String inboxRequestTableName() {
    return "InboxRequest";
  }

  public String inboxRequestTablePrimaryKeyName() {
    return "pkInboxRequest";
  }

  public String inboxResponseTableName() {
    return "InboxResponse";
  }

  public String inboxResponseTablePrimaryKeyName() {
    return "pkInboxResponse";
  }

  public String outboxResponseTableName(Subscriber subscriber) {
    return "OutboxResponse";
  }

  public String outboxResponseTablePrimaryKeyName(Subscriber subscriber) {
    return "pkOutboxResponse";
  }

  public String outboxRequestTableName(Subscriber subscriber) {
    return "OutboxRequest";
  }

  public String outboxRequestTablePrimaryKeyName(Subscriber subscriber) {
    return "pkOutboxRequest";
  }

  public String processingTableName(Subscriber subscriber) {
    return model.name() + "Queue" + capitalize(subscriber) + "Processing";
  }

  public String idTableName(SecondaryIdModel idModel) {
    return model.name() + "_" + idModel.name();
  }

  public String idTablePrimaryKeyName(SecondaryIdModel idModel) {
    return String.format("pk%s", idTableName(idModel));
  }

  public QualifiedNames qualifiedNames() {
    return new QualifiedNames(this);
  }

  public class QualifiedNames {

    private final SchemaNames entityModel;

    public QualifiedNames(SchemaNames entityModel) {
        this.entityModel = entityModel;
    }

    public String eventTable() {
      return qualifiedName(entityModel.eventTableName());
    }

    public String inboxRequestTable() {
      return qualifiedName(entityModel.inboxRequestTableName());
    }

    public String inboxResponseTable() {
      return qualifiedName(entityModel.inboxResponseTableName());
    }

    public String outboxResponseTable(Subscriber subscriber) {
      return qualifiedName(entityModel.outboxResponseTableName(subscriber));
    }

    public String outboxRequestTable(Subscriber subscriber) {
      return qualifiedName(entityModel.outboxRequestTableName(subscriber));
    }

    public String idTable(SecondaryIdModel secondaryId) {
      return qualifiedName(entityModel.idTableName(secondaryId));
    }

    public String queueTable(Subscriber subscriber) {
      return qualifiedName(entityModel.queueTableName(subscriber));
    }

    public String processingTable(Subscriber subscriber) {
      return qualifiedName(entityModel.processingTableName(subscriber));
    }

    public String dlqTable(Subscriber subscriber) {
      return qualifiedName(entityModel.dlqTableName(subscriber));
    }

    public String timeoutTable() {
      return qualifiedName(entityModel.timeoutTableName());
    }

    public String qualifiedName(String name) {
      return String.format("[%s].[%s]", schema, name);
    }
  }

  public String queueTableName(Subscriber subscriber) {
    return model.name() + "Queue" + capitalize(subscriber);
  }

  public String queueTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s", queueTableName(subscriber));
  }

  public String dlqTableName(Subscriber subscriber) {
    return model.name() + "Queue" + capitalize(subscriber) + "Dlq";
  }

  public String dlqTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s", dlqTableName(subscriber));
  }

  public interface SecondaryIdModel {
    String name();
    List<Column> columns();
    SecondaryId map(ResultSet resultSet);
    default boolean isSerial() {
      return false;
    }
    default Group group() {
      return null;
    }
    interface Group {
      List<Column> groupColumns();
      List<ColumnOrder> groupOrdering();
      boolean isInitial(Object value);
      SecondaryId initial(Object group);
      SecondaryId next(SecondaryId current);
      Object group(Object value);
    }
  }

  public record Column(String name, String type, Function<Object, ?> value) {}
  public record ColumnOrder(Column column, boolean descending) {}

  private String capitalize(Subscriber subscriber) {
    char baseChar = subscriber.name().charAt(0);
    char updatedChar = Character.toUpperCase(baseChar);
    if (baseChar == updatedChar) {
      return subscriber.name();
    } else {
      char[] chars = subscriber.name().toCharArray();
      chars[0] = updatedChar;
      return new String(chars);
    }
  }

}
