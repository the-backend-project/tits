package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EventTriggerTest.Entities.Apple;
import static com.github.thxmasj.statemachine.EventTriggerTest.Entities.Banana;
import static com.github.thxmasj.statemachine.EventTriggerTest.States.Begin;

import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;

public class EventTriggerTest {

  enum States implements State {Begin, Processing, Unreachable}

  enum Entities implements EntityModel {
    Apple {
        @Override
        public UUID id() {
            return UUID.fromString("29d5e5d6-a9d6-4899-9083-8444a5fe4cd2");
        }

        @Override
        public State initialState() {
            return Begin;
        }
    },
    Banana {
      @Override
      public UUID id() {
        return UUID.fromString("f01199a0-d2f0-4cc3-8c05-b44e0da7c3d1");
      }

      @Override
      public State initialState() {
        return Begin;
      }
    }

  }

  private static Map<State, List<TransitionModel<?, ?>>> appleTransitions() {
    return Map.of(
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> bananaTransitions() {
    return Map.of(
    );
  }

  private static StateMachine stateMachine;

  @BeforeAll
  public static void setUp() {
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions = new HashMap<>();
    transitions.put(Apple, appleTransitions());
    transitions.put(Banana, bananaTransitions());
    stateMachine = Init.stateMachine(transitions);
  }

}
