package com.github.thxmasj.backend.database.pgsql;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.Client;
import com.github.thxmasj.backend.core.database.Client.Config;
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
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.SslMode;
import reactor.core.publisher.Mono;

/// PostgreSQL
public class PgsqlDatabase implements Database {

  @Override
  public String name() {
    return "pgsql";
  }

  @Override
  public boolean isProduct(String productName) {
    return productName.equals("PostgreSQL");
  }

  @Override
  public DataSource dataSource(Config config) {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    ds.setServerNames(new String[] {config.host()});
    ds.setPortNumbers(new int[] {config.port()});
    ds.setDatabaseName(config.name());
    ds.setUser(config.user());
    ds.setPassword(config.password());
    ds.setLoginTimeout(30);
    // Trusting the server certificate means encryption without verification, if the server supports it
    ds.setSslMode((config.trustServerCertificate() ? SslMode.PREFER : SslMode.VERIFY_FULL).value);
    ds.setOptions("-c statement_timeout=" + config.queryTimeout().toMillis());
    return ds;
  }

  @Override
  public Client client(DataSource dataSource) {
    return new PgsqlClient(dataSource);
  }

  @Override
  public Storage storage(
      DataSource dataSource,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions,
      String schemaName,
      Clock clock
  ) {
    List<EntityModel> entityModels = List.copyOf(transitions.keySet());
    Map<EntityModel, List<EventType<?, ?>>> eventTypesByEntityModel = Mappers.eventTypesByEntityModel(transitions);
    var client = client(dataSource);
    return new Storage(
        new ChangeState(client, schemaName, clock),
        new EventsByEntityId(dataSource, table -> Queries.eventsByEntityId(schemaName, table), entityModels, clock),
        new EventsByIdEvent(dataSource, Queries.eventsByIdEvent(schemaName), eventTypesByEntityModel, clock),
        new EventsByLastIndexEvent(dataSource, Queries.eventsByLastIndexEvent(schemaName), clock),
        new EntitiesByIndex(dataSource, Queries.entitiesByIndex(schemaName), eventTypesByEntityModel, clock),
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
