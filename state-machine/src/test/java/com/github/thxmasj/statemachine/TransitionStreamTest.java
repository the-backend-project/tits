package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionStreamTest.Entities.Lamp;
import static com.github.thxmasj.statemachine.TransitionStreamTest.States.Off;
import static com.github.thxmasj.statemachine.TransitionStreamTest.States.On;

import java.util.UUID;
import org.junit.jupiter.api.Test;

public class TransitionStreamTest {

  enum Entities implements EntityModel {
    Lamp {
      @Override
      public UUID id() {
        return UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f");
      }

      @Override
      public State initialState() {
        return Off;
      }

    }
  }
  enum States implements State {Off, On}
  static EventType<Void, Void>
      Toggle = BasicEventType.of("Toggle", UUID.fromString("46b0211e-f583-49b3-a6e7-8d13742e0260")),
      SwitchOn = BasicEventType.of("SwitchOn", UUID.fromString("5e9a8a9d-6a21-41cf-82dc-857fe1e4c4e0")),
      SwitchOff = BasicEventType.of("SwitchOff", UUID.fromString("8e1483c8-b649-43b8-b352-5094a94c0dad"))
          ;

  @Test
  public void test() {
    onEvent(SwitchOn).to(On)
        .trigger(SwitchOn).on(Lamp).identifiedBy(_ -> entityId(UUID.randomUUID()))
        .output()
    ;
  }

}
