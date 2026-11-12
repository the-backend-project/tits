package com.github.thxmasj.backend.database.mssql;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.DatabaseClient;
import com.github.thxmasj.backend.core.database.DatabaseClient.Config;
import com.github.thxmasj.backend.core.database.ConditionalTriggers;
import com.github.thxmasj.backend.core.database.Database;
import com.github.thxmasj.backend.core.database.DeleteDelayedTrigger;
import com.github.thxmasj.backend.core.database.EntitiesByIndex;
import com.github.thxmasj.backend.core.database.EventsByEntityId;
import com.github.thxmasj.backend.core.database.EventsByIdEvent;
import com.github.thxmasj.backend.core.database.EventsByLastIndexEvent;
import com.github.thxmasj.backend.core.database.Mappers;
import com.github.thxmasj.backend.core.database.NextDelayedTrigger;
import com.github.thxmasj.backend.core.database.Storage;
import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import reactor.core.publisher.Mono;

/// SQL Server, including Azure SQL
public class MssqlDatabase implements Database {

  @Override
  public String name() {
    return "mssql";
  }

  @Override
  public boolean isProduct(String productName) {
    return productName.equals("Microsoft SQL Server");
  }

  @Override
  public DataSource dataSource(Config config) {
    SQLServerDataSource ds = new SQLServerDataSource();
    ds.setServerName(config.host());
    ds.setPortNumber(config.port());
    ds.setEncrypt("true");
    ds.setLoginTimeout(30);
    ds.setDatabaseName(config.name());
    ds.setHostNameInCertificate("*.database.windows.net");
    ds.setQueryTimeout((int)config.queryTimeout().toSeconds());
    ds.setTrustServerCertificate(config.trustServerCertificate());
    ds.setUser(config.user());
    ds.setPassword(config.password());
    return ds;
  }

  @Override
  public DatabaseClient client(DataSource dataSource) {
    return new MssqlClient(dataSource);
  }

  @Override
  public Storage storage(
      DatabaseClient client,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    List<EntityModel> entityModels = List.copyOf(transitions.keySet());
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel = Mappers.eventTypesByEntityModel(transitions);
    return new Storage(
        new ChangeState(client, schemaName, clock),
        new EventsByEntityId(client, table -> Queries.eventsByEntityId(schemaName, table), entityModels, clock),
        new EventsByIdEvent(client, Queries.eventsByIdEvent(schemaName), eventTypesByEntityModel, clock),
        new EventsByLastIndexEvent(client, Queries.eventsByLastIndexEvent(schemaName), clock),
        new EntitiesByIndex(client, Queries.entitiesByIndex(schemaName), eventTypesByEntityModel, clock),
        new NextDelayedTrigger(client, Queries.nextDelayedTrigger(schemaName), Mappers.eventTypes(transitions), clock, entityModels),
        new DeleteDelayedTrigger(client, Queries.deleteDelayedTrigger(schemaName)),
        new ConditionalTriggers(client, Queries.conditionalTriggers(schemaName), eventTypesByEntityModel, clock)
    );
  }

  @Override
  public Mono<Integer> createSchema(DataSource schemaDataSource, String schemaName, String role) {
    return new CreateSchema(schemaName, role).execute(schemaDataSource);
  }

}
