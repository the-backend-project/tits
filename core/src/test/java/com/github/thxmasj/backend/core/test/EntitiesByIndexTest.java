package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.lastReferencing;
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
import com.github.thxmasj.backend.core.internal.database.EntitiesByIndex;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
public class EntitiesByIndexTest {

  enum OrderStates implements State { Created, Shipped }

  static EntityModel Order = EntityModel.of("Order", UUID.fromString("b837db51-40e1-456a-aee1-b0dbb958c281"), EntitiesByIndexTest::orderTransitions);
  static EventType<UUID, UUID> customerReferenced = IndexEntityModel.referenceEventType("Customer referenced", UUID.fromString("56e950a0-369a-4bf9-b126-e5c762fdb6bc"));
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("6a0eb254-8e11-4f96-b4fe-c93d8b4e7232"), customerReferenced);

  static EventType<UUID, UUID> OrderCreated = BasicEventType.of("Order created", UUID.fromString("38d58c97-6a1e-450f-a399-52e185859dc1"), DataType.uuid());
  static EventType<Void, Void> OrderShipped = BasicEventType.of("Order shipped", UUID.fromString("617fb006-eb86-455b-bf98-0c6efea8cba3"));

  private static Map<State, List<TransitionModel<?, ?>>> orderTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(OrderCreated).to(OrderStates.Created)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(customerReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2()))
                .output()
        ),
        OrderStates.Created, List.of(
            onEvent(OrderShipped).to(OrderStates.Shipped).output()
        ),
        OrderStates.Shipped, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> invoiceTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(InvoiceCreated).to(OrderStates.Created)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(customerReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2()))
                .output()
        ),
        OrderStates.Created, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> shipperTransitions() {
    return Map.of(
        Begin, List.of(
            onEvent(ShippingRequested).to(ShipperStates.Requested)
                .assembleInput()
                .trigger(OrderShipped).on(Order).identifiedBy(customerId -> lastReferencing(customerReferenced, customerId))
                .output(d -> d.t1())
        ),
        ShipperStates.Requested, List.of()
    );
  }

  @Test
  public void testEntitiesByIndexResolvesCorrectEntityModelAndHandlesMultipleEvents() {

    String schema = UUID.randomUUID().toString();
    var entityModels = List.of(Order, Index);
    StateMachine machine = Init.stateMachine(schema, entityModels);
    EntitiesByIndex entitiesByIndex = Init.storage(schema, entityModels).entitiesByIndex();

    UUID customerId = UUID.randomUUID();

    // 1. Create first order for customer
    var event1 = machine.onEvent(trigger(OrderCreated, Order), customerId).blockFirst();
    assertNotNull(event1);
    UUID orderId = event1.entityId();

    // Query index
    List<EventLog> logs1 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs1);
    assertEquals(1, logs1.size());
    EventLog orderLog1 = logs1.get(0);
    assertEquals(orderId, orderLog1.entityId());
    assertEquals(Order, orderLog1.entityModel());
    assertEquals(1, orderLog1.events().size());

    // 2. Perform second transition on the same entity (eventNumber == 2)
    var event2 = machine.onEvent(trigger(OrderShipped, Order, orderId)).blockFirst();
    assertNotNull(event2);
    assertEquals(2, event2.eventNumber());

    // Query index again - should still resolve model and reflect 2 events
    List<EventLog> logs2 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs2);
    assertEquals(1, logs2.size());
    EventLog orderLog2 = logs2.get(0);
    assertEquals(orderId, orderLog2.entityId());
    assertEquals(Order, orderLog2.entityModel());
    assertEquals(2, orderLog2.events().size());

    // 3. Create a second order for the same customer
    var event3 = machine.onEvent(trigger(OrderCreated, Order), customerId).blockFirst();
    assertNotNull(event3);
    UUID orderId2 = event3.entityId();

    List<EventLog> logs3 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs3);
    assertEquals(2, logs3.size());
    assertEquals(Order, logs3.get(0).entityModel());
    assertEquals(Order, logs3.get(1).entityModel());
    List<UUID> returnedIds = logs3.stream().map(l -> l.entityId()).toList();
    assertTrue(returnedIds.contains(orderId));
    assertTrue(returnedIds.contains(orderId2));
  }

  enum ShipperStates implements State { Requested }

  static EntityModel Invoice = EntityModel.of("Invoice", UUID.fromString("0c0f4d0e-2a7b-4f53-9a43-b8f1e0b8d3a5"), EntitiesByIndexTest::invoiceTransitions);
  static EntityModel Shipper = EntityModel.of("Shipper", UUID.fromString("8a0d6c5e-5a8f-4d1f-8f25-0a7d1d0c6f4b"), EntitiesByIndexTest::shipperTransitions);
  static EventType<UUID, UUID> InvoiceCreated = BasicEventType.of("Invoice created", UUID.fromString("4b8e2f0a-6c1d-4e7b-9a3f-2d5c8b1e7f60"), DataType.uuid());
  static EventType<UUID, UUID> ShippingRequested = BasicEventType.of("Shipping requested", UUID.fromString("c3a9e1d7-0f4b-4c8e-b2d6-7e1f5a9c3b80"), DataType.uuid());

  /// The last created entity of the triggered model referencing the id is selected, not an earlier one or one of
  /// another model referencing the same id
  @Test
  public void lastReferencingSelectsLastEntityOfModel() {
    var entityModels = List.of(Order, Invoice, Shipper, Index);
    String schema = UUID.randomUUID().toString();
    StateMachine machine = Init.stateMachine(schema, entityModels);
    EntitiesByIndex entitiesByIndex = Init.storage(schema, entityModels).entitiesByIndex();

    UUID customerId = UUID.randomUUID();
    machine.onEvent(trigger(OrderCreated, Order), customerId).blockFirst(Duration.ofSeconds(10));
    UUID lastOrderId = machine.onEvent(trigger(OrderCreated, Order), customerId).blockFirst(Duration.ofSeconds(10)).entityId();
    machine.onEvent(trigger(InvoiceCreated, Invoice), customerId).blockFirst(Duration.ofSeconds(10));

    machine.onEvent(trigger(ShippingRequested, Shipper), customerId).blockFirst(Duration.ofSeconds(10));

    List<UUID> shipped = entitiesByIndex.execute(customerId, customerReferenced)
        .filter(log -> log.exists(OrderShipped))
        .map(EventLog::entityId)
        .collectList()
        .block(Duration.ofSeconds(10));
    assertEquals(List.of(lastOrderId), shipped);

    // No order references an unknown customer, so the trigger is rejected, like one on an entity id that doesn't exist,
    // while the transition triggering it is accepted
    var unknown = machine.onEvent(trigger(ShippingRequested, Shipper), UUID.randomUUID()).blockFirst(Duration.ofSeconds(10));
    assertNotNull(unknown);
    assertEquals(ShippingRequested, unknown.type());
  }

}
