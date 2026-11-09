package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityId;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.test.Init;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.runtime.StateMachine;
import com.github.thxmasj.statemachine.TransitionModel;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class ConditionTriggerTest {

  enum OrderStates implements State {Created, Shipped, Cancelled}

  enum BatchStates implements State {Open, Waiting, Closed}

  static EntityModel Order = EntityModel.of("Order", UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b01"));
  static EntityModel Batch = EntityModel.of("Batch", UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b02"));
  static EventType<UUID, UUID> orderReferenced = IndexEntityModel.referenceEventType(
      "Order referenced",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b03")
  );
  static IndexEntityModel Index = IndexEntityModel.of(
      "Index",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b04"),
      orderReferenced
  );

  static EventType<UUID, UUID> CreateOrder = BasicEventType.of(
      "CreateOrder",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b05"),
      DataType.uuid()
  );
  static EventType<Void, Void> ShipOrder = BasicEventType.of(
      "ShipOrder",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b06")
  );
  static EventType<Void, Void> CancelOrder = BasicEventType.of(
      "CancelOrder",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0b")
  );
  static EventType<UUID, UUID> CloseBatchWhenShippedOrCancelled = BasicEventType.of(
      "CloseBatchWhenShippedOrCancelled",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0c"),
      DataType.uuid()
  );
  static EventType<Void, Void> OpenBatch = BasicEventType.of(
      "OpenBatch",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b07")
  );
  static EventType<UUID, UUID> CloseBatchWhenShipped = BasicEventType.of(
      "CloseBatchWhenShipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b08"),
      DataType.uuid()
  );
  static EventType<UUID, UUID> CloseSessionBatchWhenShipped = BasicEventType.of(
      "CloseSessionBatchWhenShipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0a"),
      DataType.uuid()
  );
  static EventType<Void, Void> CloseBatch = BasicEventType.of(
      "CloseBatch",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b09")
  );

  private EventsByEntityId eventsByEntityId;

  private StateMachine stateMachine(String schema) {
    eventsByEntityId = new EventsByEntityId(
        Init.dataSource(),
        List.of(Order, Batch, Index),
        schema,
        Clock.systemUTC()
    );
    return Init.stateMachine(schema, Map.of(
        Order, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(CreateOrder).to(OrderStates.Created)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(orderReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2().value()))
                    .output()
            ),
            OrderStates.Created, List.of(
                onEvent(ShipOrder).to(OrderStates.Shipped).output(),
                onEvent(CancelOrder).to(OrderStates.Cancelled).output()
            ),
            OrderStates.Shipped, List.of(),
            OrderStates.Cancelled, List.of()
        ),
        Batch, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(onEvent(OpenBatch).to(BatchStates.Open).output()),
            BatchStates.Open, List.of(
                onEvent(CloseBatchWhenShipped).to(BatchStates.Waiting)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(CloseBatch)
                    .when(orderReferenced, t -> t.t1())
                    .is(ShipOrder)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(t -> entityId((UUID) t.t2().value()))
                    .output(),
                onEvent(CloseSessionBatchWhenShipped).to(BatchStates.Waiting)
                    .assemble(c -> c.input())
                    .trigger(CloseBatch)
                    .when(orderReferenced, t -> t)
                    .is(ShipOrder)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(CloseBatchWhenShippedOrCancelled).to(BatchStates.Waiting)
                    .assemble(c -> c.input())
                    .trigger(CloseBatch)
                    .when(orderReferenced, t -> t)
                    .is(ShipOrder, CancelOrder)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(entityIdFromSession())
                    .output()
            ),
            BatchStates.Waiting, List.of(onEvent(CloseBatch).to(BatchStates.Closed).output()),
            BatchStates.Closed, List.of()
        ),
        Index, Index.transitions()
    ));
  }

  private EventLog batchLog(UUID batchId) {
    return eventsByEntityId.execute(
        Batch,
        List.of(OpenBatch, CloseBatchWhenShipped, CloseSessionBatchWhenShipped, CloseBatchWhenShippedOrCancelled, CloseBatch),
        new EntityId.UUID(batchId)
    ).block();
  }

  private int batchEventCount(StateMachine machine, UUID batchId) {
    return batchLog(batchId).events().size();
  }

  private void awaitBatchEvents(StateMachine machine, UUID batchId, int expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
    while (System.currentTimeMillis() < deadline && batchEventCount(machine, batchId) < expected) Thread.sleep(200);
  }

  @Test
  public void waitingEventFiresWhenAllReferencedEntitiesAreInState() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order1 = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(OpenBatch, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: no order is shipped, so the close event must wait
    machine.onEvent(trigger(CloseBatchWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition still not met: only one of two orders is shipped
    machine.onEvent(trigger(ShipOrder, Order, order1.entityId())).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the waiting event fires within about a second
    machine.onEvent(trigger(ShipOrder, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(CloseBatch.name(), log.events().getLast().typeName());

    // The row was deleted with the fired event's change, so it is not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void sessionIdentifiedTargetWaitsAndFiresWhenConditionIsMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    assertNotNull(order);
    var batch = machine.onEvent(trigger(OpenBatch, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: the order is not shipped, so the close event waits
    machine.onEvent(trigger(CloseSessionBatchWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the event fires on the session's own entity
    machine.onEvent(trigger(ShipOrder, Order, order.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(CloseBatch.name(), log.events().getLast().typeName());

    // Not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void waitingEventFiresWhenAllReferencedEntitiesAreInOneOfTheStates() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order1 = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(OpenBatch, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: one order is cancelled, the other is neither shipped nor cancelled
    machine.onEvent(trigger(CancelOrder, Order, order1.entityId())).blockFirst();
    machine.onEvent(trigger(CloseBatchWhenShippedOrCancelled, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: one order is cancelled and the other is shipped
    machine.onEvent(trigger(ShipOrder, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(CloseBatch.name(), log.events().getLast().typeName());
  }

  @Test
  public void sessionIdentifiedTargetFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(ShipOrder, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(OpenBatch, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(CloseSessionBatchWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

  @Test
  public void eventFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(CreateOrder, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(ShipOrder, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(OpenBatch, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(CloseBatchWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

}
