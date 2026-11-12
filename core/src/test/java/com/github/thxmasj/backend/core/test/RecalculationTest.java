package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.IdEntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.core.Tuples.Tuple2;
import com.github.thxmasj.backend.core.database.IdEventAlreadyExists;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// The changes of an event are calculated again when one of their ids is found to exist, from the trigger of the id
@Tag("database")
public class RecalculationTest {

  enum States implements State { CheckedOut, Placed, Reserved, Rejected }

  /// Ignores the case of a code, such that the id of an id event may differ from the data it is triggered with
  static class CaseInsensitive implements DataType<String> {
    @Override public String name() {return "CaseInsensitive";}
    @Override public byte[] marshal(String value) {return value.toUpperCase().getBytes(UTF_8);}
    @Override public String unmarshal(byte[] value) {return new String(value, UTF_8);}
  }

  static EventType<String, String> OrderNumber = BasicEventType.of("OrderNumber", UUID.fromString("4b1d7e52-9c3a-4f68-8e0b-2a5d6c7f8e91"), DataType.string());
  static EventType<String, String> Code = BasicEventType.of("Code", UUID.fromString("0f2b4d6e-8a9c-4e1f-b3d5-7f9b1d3f5ad5"), DataType.string(), new CaseInsensitive());
  static IdEntityModel Ids = IdEntityModel.of("Ids", UUID.fromString("c2e8f4a6-0b1d-4c3e-9f5a-7b9d1e3f5a72"), OrderNumber, Code);

  static EntityModel Cart = EntityModel.of("Cart", UUID.fromString("8f3a5c7e-1b2d-4e6f-a8c0-3d5f7a9b1c24"));
  static EntityModel Order = EntityModel.of("Order", UUID.fromString("1e3f5a7c-9b0d-4c2e-8a4f-6b8d0e2f4a65"));
  static EntityModel Stock = EntityModel.of("Stock", UUID.fromString("6d8f0b2c-4e5a-4a7c-9e1b-3c5d7f9a1b38"));

  static EventType<String, String> Checkout = BasicEventType.of("Checkout", UUID.fromString("a7c9e1b3-5d6f-4b8a-9c2e-4f6a8c0e2b47"), DataType.string());
  static EventType<String, String> Forward = BasicEventType.of("Forward", UUID.fromString("3c5e7a9b-1d2f-4a4c-8e6b-0d2f4a6c8e59"), DataType.string());
  static EventType<String, String> Place = BasicEventType.of("Place", UUID.fromString("e9b1d3f5-7a8c-4d0e-a2f4-6c8e0a2c4e61"), DataType.string());
  static EventType<String, String> Buy = BasicEventType.of("Buy", UUID.fromString("5a7c9e1b-3d4f-4c6a-8e0b-2d4f6a8c0e73"), DataType.string());
  static EventType<String, String> Tag = BasicEventType.of("Tag", UUID.fromString("6b8d0f2a-4c5e-4d7b-9f1c-3e5a7c9e1be7"), DataType.string());
  static EventType<String, String> Accept = BasicEventType.of("Accept", UUID.fromString("b3d5f7a9-1c2e-4e4a-9c6d-8f0b2d4f6a85"), DataType.string());
  static EventType<String, String> RejectDuplicate = BasicEventType.of("RejectDuplicate", UUID.fromString("7e9a1c3d-5f6b-4a8c-a0e2-4b6d8f0a2c97"), DataType.string());
  static EventType<String, String> RejectUnreserved = BasicEventType.of("RejectUnreserved", UUID.fromString("2f4b6d8e-0a1c-4c3e-8f5a-7c9e1b3d5fa9"), DataType.string());
  static EventType<String, String> Reserve = BasicEventType.of("Reserve", UUID.fromString("d1f3a5c7-9e0b-4b2d-a4f6-8b0d2f4a6cb1"), DataType.string());
  static EventType<String, String> Refuse = BasicEventType.of("Refuse", UUID.fromString("9c1e3a5b-7d8f-4f0a-8c2e-4a6c8e0b2dc3"), DataType.string());

  private static final StateMachine machine = Init.stateMachine(UUID.randomUUID().toString(), transitions());
  // The steps executed, in order
  private static final List<String> executed = new CopyOnWriteArrayList<>();

  private static <T> T executed(String step, T result) {
    executed.add(step);
    return result;
  }

  private static Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions() {
    return Map.of(
        Cart, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                // The order is placed by a transition chosen by this one
                onEvent(Checkout).to(States.CheckedOut)
                    .assemble(c -> executed("Assemble checkout", c.input()))
                    .otherwise(
                        onEvent(Forward).to(States.CheckedOut)
                            .assemble(c -> executed("Assemble forward", c.input()))
                            .trigger(Place).with(d -> d).on(Order).identifiedBy(newEntityId())
                            .output(d -> executed("Output forward", d.t2().accepted().event().getUnmarshalledData())),
                        d -> executed("Forward data", d)
                    )
            )
        ),
        Order, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(Place).to(States.Placed)
                    .assemble(c -> executed("Assemble order", tuple(c.input(), c.log().entityId())))
                    .trigger(Reserve).withReactive(d -> Mono.fromSupplier(() -> executed("Reserve data", d.t1()))).on(Stock).identifiedBy(newEntityId())
                    .trigger(OrderNumber).with(d -> executed("Order number data", d.t1().t1())).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                    .output(d -> executed("Output order", d.t2().isIdExists() ?
                        "Duplicate of " + d.t2().idExists().log().entityId() :
                        d.t1().t1().t1())),
                // The rejected reservation is handled by a step after the trigger of the order number, and only if the
                // number does not exist
                onEvent(Buy).to(States.Placed)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(Refuse).with(Tuple2::t1).on(Stock).identifiedBy(newEntityId())
                    .trigger(OrderNumber).with(d -> d.t1().t1()).on(Ids).identifiedBy(d -> entityId(d.t1().t2(), CreateIfNotExists))
                    .when(d -> d.t2().isIdExists()).then(
                        onEvent(RejectDuplicate).to(States.Rejected).assembleInput().output(d -> d),
                        d -> d.t1().t1().t1()
                    )
                    .when(d -> d.t1().t2().isRejected()).then(
                        onEvent(RejectUnreserved).to(States.Rejected).assembleInput().output(d -> d),
                        d -> d.t1().t1().t1()
                    )
                    .otherwise(
                        onEvent(Accept).to(States.Placed).assembleInput().output(d -> d),
                        d -> d.t1().t1().t1()
                    ),
                onEvent(Tag).to(States.Placed)
                    .assemble(c -> tuple(c.input(), c.log().entityId()))
                    .trigger(Code).with(Tuple2::t1).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                    .output(d -> d.t1().t1())
            ),
            States.Placed, List.of(),
            States.Rejected, List.of()
        ),
        Stock, Map.<State, List<TransitionModel<?, ?>>>of(
            Begin, List.of(
                onEvent(Reserve).to(States.Reserved).assemble(c -> executed("Assemble reservation", c.input())).output(d -> d),
                onEvent(Refuse).to(States.Reserved)
                    .<String>assemble(c -> {
                      throw new RejectedEvent(Refuse, c.log(), c.from());
                    })
                    .output(d -> d)
            ),
            States.Reserved, List.of()
        ),
        Ids, Ids.transitions()
    );
  }

  @Test
  public void stepsBeforeTheTriggerOfAnExistingIdAreNotExecutedAgain() {
    Event<?> first = machine.onEvent(trigger(Place, Order), "1").blockFirst();
    executed.clear();

    Event<?> second = machine.onEvent(trigger(Place, Order), "1").blockFirst();

    assertEquals("Duplicate of " + first.entityId(), second.getUnmarshalledData());
    // Only the output is a step after the trigger, so it alone is executed again, with the id existing
    assertEquals(
        List.of("Assemble order", "Reserve data", "Assemble reservation", "Order number data", "Output order", "Output order"),
        executed
    );
  }

  @Test
  public void stepsOfTheTriggeringTransitionsAreNotExecutedAgainEither() {
    machine.onEvent(trigger(Checkout, Cart), "2").blockFirst();
    executed.clear();

    Event<?> second = machine.onEvent(trigger(Checkout, Cart), "2").blockFirst();

    assertEquals("Forward", second.typeName());
    assertEquals(0, second.getUnmarshalledData().toString().indexOf("Duplicate of "));
    // The outputs of the transitions the trigger is a part of are steps after it too
    assertEquals(
        List.of(
            "Assemble checkout", "Forward data", "Assemble forward",
            "Assemble order", "Reserve data", "Assemble reservation", "Order number data", "Output order",
            "Output forward",
            "Output order",
            "Output forward"
        ),
        executed
    );
  }

  @Test
  public void existingIdNotTriggeredByATransitionFails() {
    machine.onEvent(trigger(Place, Order), "3").blockFirst();

    // The id event is the event itself, so calculating the changes again would not change them
    assertThrows(
        IdEventAlreadyExists.class,
        () -> machine.onEvent(trigger(OrderNumber, Ids), "3").blockFirst(Duration.ofSeconds(10))
    );
  }

  @Test
  public void existingIdNotFoundByTheDataItIsTriggeredWithFails() {
    machine.onEvent(trigger(Tag, Order), "a").blockFirst();

    // The trigger does not result in the id existing, so calculating the changes again would not change them either
    assertThrows(
        IdEventAlreadyExists.class,
        () -> machine.onEvent(trigger(Tag, Order), "a").blockFirst(Duration.ofSeconds(10))
    );
  }

  @Test
  public void rejectionBeforeTheTriggerMustBeHandledWhenCalculatingAgainToo() {
    Event<?> first = machine.onEvent(trigger(Buy, Order), "4").blockFirst();
    assertEquals("RejectUnreserved", first.typeName());

    // The rejected reservation was handled when the order number was assumed not to exist, but is not when it does
    assertThrows(RejectedEvent.class, () -> machine.onEvent(trigger(Buy, Order), "4").blockFirst());
  }

}
