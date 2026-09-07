package com.github.thxmasj.statemachine.http.outbox;

import com.github.thxmasj.statemachine.EventReference;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.SecondaryId;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public sealed interface HttpOutboxRequest<I> extends com.github.thxmasj.statemachine.EntityModel permits AtLeastOnce, AtMostOnce {

  SecondaryIdModel<EventReference> ProcessReference = new SecondaryIdModel<>() {
    @Override
    public String name() {
      return "ProcessReference";
    }

    @Override
    public List<Column> columns() {
      return List.of(
          new Column("ProcessId", "UNIQUEIDENTIFIER", r -> ((EventReference)r).entityId()),
          new Column("EventNumber", "SMALLINT", r -> ((EventReference)r).eventNumber())
      );
    }

    @Override
    public SecondaryId<EventReference> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(
            this,
            new EventReference(resultSet.getObject("ProcessId", UUID.class), resultSet.getInt("EventNumber"))
        );
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };

  @Override
  default List<SecondaryIdModel<?>> secondaryIds() {
    return List.of(ProcessReference);
  }

  Map<State, List<TransitionModelBuilder.TransitionModel<?, ?>>> transitions();

  EventType<I, HttpRequestMessage> requestDispatched();

  static AtLeastOnceBuilder.NameStep atLeastOnce() {
    return AtLeastOnceBuilder.create();
  }

  static AtMostOnceBuilder.NameStep atMostOnce() {
    return AtMostOnceBuilder.create();
  }
}
