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
    return String.format("%sEvent", model.name());
  }

  public String eventTablePrimaryKeyName() {
    return String.format("pk%sEvent", model.name());
  }

  public String indexName(List<Column> columns) {
    return String.format("ix%s", String.join("_", columns.stream().map(Column::name).toList()));
  }

  public String timeoutTableName() {
    return String.format("%sTimeout", model.name());
  }

  public String notificationQueueProcessingTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s", processingTableName(subscriber));
  }

  public String inboxTableName() {
    return model.name() + "Inbox";
  }

  public String inboxTablePrimaryKeyName() {
    return String.format("pk%sInbox", model.name());
  }

  public String outboxTableName() {
    return model.name() + "Outbox";
  }

  public String outboxTablePrimaryKeyName() {
    return String.format("pk%sOutbox", model.name());
  }

  public String inboxTableName(Subscriber subscriber) {
    return model.name() + capitalize(subscriber) + "Inbox";
  }

  public String inboxTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s%sInbox", model.name(), capitalize(subscriber));
  }

  public String outboxTableName(Subscriber subscriber) {
    return model.name() + capitalize(subscriber) + "Outbox";
  }

  public String outboxTablePrimaryKeyName(Subscriber subscriber) {
    return String.format("pk%s%sOutbox", model.name(), capitalize(subscriber));
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

    public String inboxTable() {
      return qualifiedName(entityModel.inboxTableName());
    }

    public String outboxTable() {
      return qualifiedName(entityModel.outboxTableName());
    }

    public String inboxTable(Subscriber subscriber) {
      return qualifiedName(entityModel.inboxTableName(subscriber));
    }

    public String outboxTable(Subscriber subscriber) {
      return qualifiedName(entityModel.outboxTableName(subscriber));
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
