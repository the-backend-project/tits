package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IndexEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.database.EventsByEntityId;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
public class ConditionalTriggerTest {

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

  static EventType<UUID, UUID> OrderCreated = BasicEventType.of(
      "Order created",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b05"),
      DataType.uuid()
  );
  static EventType<Void, Void> OrderShipped = BasicEventType.of(
      "Order shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b06")
  );
  static EventType<Void, Void> OrderCancelled = BasicEventType.of(
      "Order cancelled",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0b")
  );
  static EventType<UUID, UUID> BatchCloseRequestedWhenShippedOrCancelled = BasicEventType.of(
      "Batch close requested when shipped or cancelled",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0c"),
      DataType.uuid()
  );
  static EventType<Void, Void> BatchOpened = BasicEventType.of(
      "Batch opened",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b07")
  );
  static EventType<UUID, UUID> BatchCloseRequestedWhenShipped = BasicEventType.of(
      "Batch close requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b08"),
      DataType.uuid()
  );
  static EventType<UUID, UUID> SessionBatchCloseRequestedWhenShipped = BasicEventType.of(
      "Session batch close requested when shipped",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b0a"),
      DataType.uuid()
  );
  static EventType<Void, Void> BatchClosed = BasicEventType.of(
      "Batch closed",
      UUID.fromString("c1a8a3e0-0f0b-4b0e-9b0e-2f6d1f1c2b09")
  );

  private EventsByEntityId eventsByEntityId;

  private StateMachine stateMachine(String schema) {
    eventsByEntityId = Init.storage(schema, transitions()).eventsByEntityId();
    return Init.stateMachine(schema, transitions());
  }

  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Order, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(OrderCreated).to(OrderStates.Created)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(orderReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2()))
                    .output()
            ),
            OrderStates.Created, List.of(
                onEvent(OrderShipped).to(OrderStates.Shipped).output(),
                onEvent(OrderCancelled).to(OrderStates.Cancelled).output()
            ),
            OrderStates.Shipped, List.of(),
            OrderStates.Cancelled, List.of()
        ),
        Batch, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(onEvent(BatchOpened).to(BatchStates.Open).output()),
            BatchStates.Open, List.of(
                onEvent(BatchCloseRequestedWhenShipped).to(BatchStates.Waiting)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(BatchClosed)
                    .when(orderReferenced, t -> t.t1())
                    .is(OrderShipped)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(t -> entityId((UUID) t.t2()))
                    .output(),
                onEvent(SessionBatchCloseRequestedWhenShipped).to(BatchStates.Waiting)
                    .assemble(c -> c.input())
                    .trigger(BatchClosed)
                    .when(orderReferenced, t -> t)
                    .is(OrderShipped)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(entityIdFromSession())
                    .output(),
                onEvent(BatchCloseRequestedWhenShippedOrCancelled).to(BatchStates.Waiting)
                    .assemble(c -> c.input())
                    .trigger(BatchClosed)
                    .when(orderReferenced, t -> t)
                    .is(OrderShipped, OrderCancelled)
                    .with(_ -> null)
                    .on(Batch)
                    .identifiedBy(entityIdFromSession())
                    .output()
            ),
            BatchStates.Waiting, List.of(onEvent(BatchClosed).to(BatchStates.Closed).output()),
            BatchStates.Closed, List.of()
        ),
        Index, Index.transitions()
    );
  }

  private EventLog batchLog(UUID batchId) {
    return eventsByEntityId.execute(
        Batch,
        List.of(BatchOpened, BatchCloseRequestedWhenShipped, SessionBatchCloseRequestedWhenShipped, BatchCloseRequestedWhenShippedOrCancelled, BatchClosed),
        batchId
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
    var order1 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: no order is shipped, so the close event must wait
    machine.onEvent(trigger(BatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition still not met: only one of two orders is shipped
    machine.onEvent(trigger(OrderShipped, Order, order1.entityId())).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the waiting event fires within about a second
    machine.onEvent(trigger(OrderShipped, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());

    // The row was deleted with the fired event's change, so it is not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void sessionIdentifiedTargetWaitsAndFiresWhenConditionIsMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: the order is not shipped, so the close event waits
    machine.onEvent(trigger(SessionBatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: the event fires on the session's own entity
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());

    // Not fired again
    Thread.sleep(2500);
    assertEquals(3, batchEventCount(machine, batchId));
  }

  @Test
  public void waitingEventFiresWhenAllReferencedEntitiesAreInOneOfTheStates() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order1 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    var order2 = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order1);
    assertNotNull(order2);
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    // Condition not met: one order is cancelled, the other is neither shipped nor cancelled
    machine.onEvent(trigger(OrderCancelled, Order, order1.entityId())).blockFirst();
    machine.onEvent(trigger(BatchCloseRequestedWhenShippedOrCancelled, Batch, batchId), key).blockFirst();
    Thread.sleep(2500);
    assertEquals(2, batchEventCount(machine, batchId));

    // Condition met: one order is cancelled and the other is shipped
    machine.onEvent(trigger(OrderShipped, Order, order2.entityId())).blockFirst();
    awaitBatchEvents(machine, batchId, 3);
    var log = batchLog(batchId);
    assertEquals(3, log.events().size());
    assertEquals(BatchClosed.name(), log.events().getLast().typeName());
  }

  @Test
  public void sessionIdentifiedTargetFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(SessionBatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

  @Test
  public void eventFiresWhenConditionIsAlreadyMet() throws Exception {
    var machine = stateMachine(UUID.randomUUID().toString());
    UUID key = UUID.randomUUID();
    var order = machine.onEvent(trigger(OrderCreated, Order), key).blockFirst();
    assertNotNull(order);
    machine.onEvent(trigger(OrderShipped, Order, order.entityId())).blockFirst();
    var batch = machine.onEvent(trigger(BatchOpened, Batch)).blockFirst();
    assertNotNull(batch);
    UUID batchId = batch.entityId();

    machine.onEvent(trigger(BatchCloseRequestedWhenShipped, Batch, batchId), key).blockFirst();

    awaitBatchEvents(machine, batchId, 3);
    assertTrue(batchEventCount(machine, batchId) >= 3);
  }

}
