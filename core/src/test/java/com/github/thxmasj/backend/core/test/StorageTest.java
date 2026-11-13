package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.ChangeState.Change;
import com.github.thxmasj.backend.core.database.IdEventAlreadyExists;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.core.database.UnknownEntity;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/// Verifies the storage on the database selected for the tests (see [Init])
@Tag("database")
public class StorageTest {

  enum States implements State {Created}

  static EventType<Void, Void> PersonCreated = BasicEventType.of("Person created", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c01"));
  static EventType<String, String> NameClaimed = BasicEventType.of("Name claimed", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c02"), DataType.string());

  static EntityModel Person = EntityModel.of("Person", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c03"));
  static IdEntityModel Names = IdEntityModel.of("Names", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c04"), NameClaimed);

  private static final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = Map.of(
      Person, Map.of(Begin, List.of(onEvent(PersonCreated).to(States.Created).output())),
      Names, Names.transitions()
  );

  private static final Clock clock = Clock.systemUTC();

  private static Storage storage() {
    String schema = UUID.randomUUID().toString();
    Storage.createSchema(Init.migrationDataSource(), schema, "Test").block();
    return Init.storage(schema, transitions);
  }

  private static void store(Storage storage, Change... changes) {
    storage.changeState().execute(ZonedDateTime.now(clock), UUID.randomUUID().toString(), List.of(changes)).blockLast();
  }

  /// A person with a name, i.e. its first event and its name's id event
  private static Change[] person(UUID entityId, String name) {
    return new Change[] {
        change(Person, new Event<>(entityId, 1, PersonCreated, clock)),
        change(Names, new Event<>(entityId, 1, NameClaimed, clock, name))
    };
  }

  private static Change change(EntityModel entityModel, Event<?> event) {
    return new Change() {
      @Override public EntityModel entityModel() {return entityModel;}
      @Override public UUID entityId() {return event.entityId();}
      @Override public Event<?> newEvent() {return event;}
      @Override public DelayedEvent<?> delayedEvent() {return null;}
    };
  }

  @Test
  public void entityIsFoundByItsId() {
    Storage storage = storage();
    UUID alice = UUID.randomUUID();
    store(storage, person(alice, "Alice"));

    EventLog log = storage.eventsByIdEvent().execute(NameClaimed, NameClaimed.outputDataType().marshal("Alice")).block();

    assertEquals(alice, log.entityId());
    assertEquals(Person, log.entityModel());
    assertEquals(List.of("1:Person created"), log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertNull(storage.eventsByIdEvent().execute(NameClaimed, NameClaimed.outputDataType().marshal("Bob")).block());
  }

  @Test
  public void takenIdIsRejected() {
    Storage storage = storage();
    store(storage, person(UUID.randomUUID(), "Alice"));

    var e = assertThrows(IdEventAlreadyExists.class, () -> store(storage, person(UUID.randomUUID(), "Alice")));

    assertEquals("Alice", e.idEvent().getUnmarshalledData());
  }

  @Test
  public void changesAreRolledBackWhenOneOfThemFails() {
    Storage storage = storage();
    store(storage, person(UUID.randomUUID(), "Alice"));
    UUID bob = UUID.randomUUID();

    assertThrows(IdEventAlreadyExists.class, () -> store(storage, person(bob, "Alice")));

    assertThrows(UnknownEntity.class, () -> storage.eventsByEntityId().execute(Person, List.of(PersonCreated), bob).block());
  }

  @Test
  public void schemaIsCreatedOnceWhenCreatedConcurrently() {
    String schema = UUID.randomUUID().toString();

    List<Integer> results = Flux.fromStream(IntStream.range(0, 5).boxed())
        .flatMap(_ -> Storage.createSchema(Init.migrationDataSource(), schema, "Test").subscribeOn(Schedulers.boundedElastic()))
        .collectList()
        .block();

    assertEquals(5, results.size());
    assertEquals(4, results.stream().filter(result -> result == 0).count());
  }

  @Test
  public void existingSchemaIsVerifiedWhileItsCreationIsLocked() throws SQLException {
    String schema = UUID.randomUUID().toString();
    Storage.createSchema(Init.migrationDataSource(), schema, "Test").block();

    try (Connection connection = Init.migrationDataSource().getConnection()) {
      lockCreation(connection, schema);

      // Waiting for the lock would take until the query timeout of 10 seconds
      assertEquals(0, Storage.createSchema(Init.migrationDataSource(), schema, "Test").block(Duration.ofSeconds(5)));
    }
  }

  /// Gets the lock CreateSchema holds while creating the schema, until the connection is closed
  private static void lockCreation(Connection connection, String schema) throws SQLException {
    connection.setAutoCommit(false);
    String sql = Init.pgsql() ?
        "{call pg_advisory_xact_lock(hashtext(?))}" :
        "{call sp_getapplock(?, 'Exclusive', 'Session')}";
    try (CallableStatement lock = connection.prepareCall(sql)) {
      lock.setString(1, "CreateSchema " + schema);
      lock.execute();
    }
  }

  @Test
  public void connectionSettingsAreRestoredWhenSchemaIsCreatedAndVerified() throws SQLException {
    String schema = UUID.randomUUID().toString();
    try (Connection connection = Init.migrationDataSource().getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      DataSource pool = unresettingPool(connection);

      assertEquals(1, Storage.createSchema(pool, schema, "Test").block());
      assertFalse(connection.getAutoCommit());
      assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());

      assertEquals(0, Storage.createSchema(pool, schema, "Test").block());
      assertFalse(connection.getAutoCommit());
      assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
    }
  }

  /// A pool of the connection which, like some pools, does not reset its settings when it is returned
  private static DataSource unresettingPool(Connection connection) {
    Connection pooled = (Connection) Proxy.newProxyInstance(
        Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class},
        (_, method, args) -> {
          if (method.getName().equals("close"))
            return null;
          try {
            return method.invoke(connection, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        }
    );
    return new DataSource() {
      @Override public Connection getConnection() {return pooled;}
      @Override public Connection getConnection(String username, String password) {return pooled;}
      @Override public PrintWriter getLogWriter() {return null;}
      @Override public void setLogWriter(PrintWriter out) {}
      @Override public void setLoginTimeout(int seconds) {}
      @Override public int getLoginTimeout() {return 0;}
      @Override public java.util.logging.Logger getParentLogger() {return null;}
      @Override public <T> T unwrap(Class<T> iface) {return null;}
      @Override public boolean isWrapperFor(Class<?> iface) {return false;}
    };
  }

}
