package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.DelayedEvent;
import com.github.thxmasj.backend.core.EntityId;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.core.database.ChangeState.Change;
import com.github.thxmasj.backend.core.database.IdEventAlreadyExists;
import com.github.thxmasj.backend.core.database.Storage;
import com.github.thxmasj.backend.core.database.UnknownEntity;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/// Verifies the storage on the database selected for the tests (see [Init])
@Tag("database")
public class StorageTest {

  enum States implements State {Created}

  static EventType<Void, Void> Create = BasicEventType.of("Create", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c01"));
  static EventType<String, String> Name = BasicEventType.of("Name", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c02"), DataType.string());

  static EntityModel Person = EntityModel.of("Person", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c03"));
  static IdEntityModel Names = IdEntityModel.of("Names", UUID.fromString("5a4bd3b2-5d0e-4a8e-9a61-2f3b1d6e0c04"), Name);

  private static final Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = Map.of(
      Person, Map.of(Begin, List.of(onEvent(Create).to(States.Created).output())),
      Names, Names.transitions()
  );

  private static final Clock clock = Clock.systemUTC();

  private static Storage storage() {
    String schema = UUID.randomUUID().toString();
    Storage.createSchema(Init.migrationDataSource(), schema, "Test").block();
    return Storage.of(Init.dataSource(), transitions, schema, clock);
  }

  private static void store(Storage storage, Change... changes) {
    storage.changeState().execute(ZonedDateTime.now(clock), UUID.randomUUID().toString(), List.of(changes)).blockLast();
  }

  /// A person with a name, i.e. its first event and its name's id event
  private static Change[] person(UUID entityId, String name) {
    return new Change[] {
        change(Person, new Event<>(entityId, 1, Create, clock)),
        change(Names, new Event<>(entityId, 1, Name, clock, name))
    };
  }

  private static Change change(EntityModel entityModel, Event<?> event) {
    return new Change() {
      @Override public EntityModel entityModel() {return entityModel;}
      @Override public EntityId entityId() {return new EntityId.UUID(event.entityId());}
      @Override public Event<?> newEvent() {return event;}
      @Override public DelayedEvent<?> delayedEvent() {return null;}
    };
  }

  @Test
  public void entityIsFoundByItsId() {
    Storage storage = storage();
    UUID alice = UUID.randomUUID();
    store(storage, person(alice, "Alice"));

    EventLog log = storage.eventsByIdEvent().execute(Name, Name.outputDataType().marshal("Alice")).block();

    assertEquals(new EntityId.UUID(alice), log.entityId());
    assertEquals(Person, log.entityModel());
    assertEquals(List.of("1:Create"), log.events().stream().map(e -> e.eventNumber() + ":" + e.typeName()).toList());
    assertNull(storage.eventsByIdEvent().execute(Name, Name.outputDataType().marshal("Bob")).block());
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

    assertThrows(UnknownEntity.class, () -> storage.eventsByEntityId().execute(Person, List.of(Create), new EntityId.UUID(bob)).block());
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

}
