package com.github.thxmasj.statemachine.database.mssql;

import static com.github.thxmasj.statemachine.EntityModel.Begin;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.database.mssql.Mappers.eventTypesFor;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.thxmasj.statemachine.BasicEventType;
import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.DataType;
import com.github.thxmasj.statemachine.EntityModel;
import com.github.thxmasj.statemachine.EventLog;
import com.github.thxmasj.statemachine.EventType;
import com.github.thxmasj.statemachine.IndexEntityModel;
import com.github.thxmasj.statemachine.test.Init;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.runtime.StateMachine;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class EntitiesByIndexTest {

  enum OrderStates implements State { Created, Shipped }

  static EntityModel Order = EntityModel.of("Order", UUID.fromString("b837db51-40e1-456a-aee1-b0dbb958c281"));
  static EventType<UUID, UUID> customerReferenced = IndexEntityModel.referenceEventType("Customer referenced", UUID.fromString("56e950a0-369a-4bf9-b126-e5c762fdb6bc"));
  static IndexEntityModel Index = IndexEntityModel.of("Index", UUID.fromString("6a0eb254-8e11-4f96-b4fe-c93d8b4e7232"), customerReferenced);

  static EventType<UUID, UUID> CreateOrder = BasicEventType.of("CreateOrder", UUID.fromString("38d58c97-6a1e-450f-a399-52e185859dc1"), DataType.uuid());
  static EventType<Void, Void> ShipOrder = BasicEventType.of("ShipOrder", UUID.fromString("617fb006-eb86-455b-bf98-0c6efea8cba3"));

  @Test
  public void testEntitiesByIndexResolvesCorrectEntityModelAndHandlesMultipleEvents() {
    Map<State, List<TransitionModel<?, ?>>> orderTransitions = Map.of(
        Begin, List.of(
            onEvent(CreateOrder).to(OrderStates.Created)
                .assemble(c -> tuple(c.input(), c.log().entityId()))
                .trigger(customerReferenced).with(t -> t.t1()).on(Index).identifiedBy(t -> newEntityId(t.t2().value()))
                .output()
        ),
        OrderStates.Created, List.of(
            onEvent(ShipOrder).to(OrderStates.Shipped).output()
        ),
        OrderStates.Shipped, List.of()
    );

    String schema = UUID.randomUUID().toString();
    var transitions = Map.of(
        Order, orderTransitions,
        Index, Index.transitions()
    );
    Map<EntityModel, List<EventType<?, ?>>> eventsByEntityModel = transitions.entrySet().stream()
        .collect(toMap(
            e -> e.getKey(),
            e -> Stream.concat(
                    BuiltinEventTypes.ALL.stream(),
                    e.getValue().values().stream().flatMap(tl -> tl.stream()).flatMap(t -> eventTypesFor(t).stream())
                )
                .distinct().toList()
        ));
    StateMachine machine = Init.stateMachine(schema, transitions);
    EntitiesByIndex entitiesByIndex = new EntitiesByIndex(
        Init.dataSource(),
        List.of(Order, Index),
        schema,
        List.of(CreateOrder, customerReferenced, ShipOrder),
        Clock.systemUTC()
    );

    UUID customerId = UUID.randomUUID();

    // 1. Create first order for customer
    var event1 = machine.onEvent(trigger(CreateOrder, Order), customerId).blockFirst();
    assertNotNull(event1);
    UUID orderId = event1.entityId();

    // Query index
    List<EventLog> logs1 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs1);
    assertEquals(1, logs1.size());
    EventLog orderLog1 = logs1.get(0);
    assertEquals(orderId, orderLog1.entityId().value());
    assertEquals(Order, orderLog1.entityModel());
    assertEquals(1, orderLog1.events().size());

    // 2. Perform second transition on the same entity (eventNumber == 2)
    var event2 = machine.onEvent(trigger(ShipOrder, Order, orderId)).blockFirst();
    assertNotNull(event2);
    assertEquals(2, event2.eventNumber());

    // Query index again - should still resolve model and reflect 2 events
    List<EventLog> logs2 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs2);
    assertEquals(1, logs2.size());
    EventLog orderLog2 = logs2.get(0);
    assertEquals(orderId, orderLog2.entityId().value());
    assertEquals(Order, orderLog2.entityModel());
    assertEquals(2, orderLog2.events().size());

    // 3. Create a second order for the same customer
    var event3 = machine.onEvent(trigger(CreateOrder, Order), customerId).blockFirst();
    assertNotNull(event3);
    UUID orderId2 = event3.entityId();

    List<EventLog> logs3 = entitiesByIndex.execute(customerId, customerReferenced).collectList().block();
    assertNotNull(logs3);
    assertEquals(2, logs3.size());
    assertEquals(Order, logs3.get(0).entityModel());
    assertEquals(Order, logs3.get(1).entityModel());
    List<UUID> returnedIds = logs3.stream().map(l -> (UUID) l.entityId().value()).toList();
    assertTrue(returnedIds.contains(orderId));
    assertTrue(returnedIds.contains(orderId2));
  }

}
