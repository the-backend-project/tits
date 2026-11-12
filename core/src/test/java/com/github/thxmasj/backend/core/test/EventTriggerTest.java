package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.test.EventTriggerTest.Entities.Apple;
import static com.github.thxmasj.backend.core.test.EventTriggerTest.Entities.Banana;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

@Tag("database")
public class EventTriggerTest {

  enum States implements State {Begin, Processing, Unreachable}

  enum Entities implements EntityModel {
    Apple {
        @Override
        public UUID id() {
            return UUID.fromString("29d5e5d6-a9d6-4899-9083-8444a5fe4cd2");
        }
    },
    Banana {
      @Override
      public UUID id() {
        return UUID.fromString("f01199a0-d2f0-4cc3-8c05-b44e0da7c3d1");
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
