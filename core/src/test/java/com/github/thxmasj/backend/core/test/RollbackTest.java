package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.BuiltinEventTypes.EventsRolledBack;
import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.test.RollbackTest.Entities.Pacman;
import static com.github.thxmasj.backend.core.test.RollbackTest.Entities.Speed;
import static com.github.thxmasj.backend.core.test.RollbackTest.PacmanStates.Dead;
import static com.github.thxmasj.backend.core.test.RollbackTest.PacmanStates.Moving;
import static com.github.thxmasj.backend.core.test.RollbackTest.PacmanStates.Stopped;
import static com.github.thxmasj.backend.core.test.RollbackTest.SpeedStates.Fast;
import static com.github.thxmasj.backend.core.test.RollbackTest.SpeedStates.Normal;
import static com.github.thxmasj.backend.core.test.RollbackTest.SpeedStates.Slow;
import static com.github.thxmasj.backend.core.test.RollbackTest.SpeedStates.Zero;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.assemble;

import com.github.thxmasj.backend.core.BasicEventType.Rollback.Data;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.EntitySelector;
import com.github.thxmasj.backend.core.EventLog;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine.RejectedEvent;
import com.github.thxmasj.backend.core.StateMachine;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@Tag("database")
public class RollbackTest {

  enum PacmanStates implements State {Stopped, Moving, Dead}
  enum SpeedStates implements State {Zero, Normal, Slow, Fast}

  enum Entities implements EntityModel {
    Pacman {
      @Override public UUID id() {return UUID.fromString("15f23da6-19be-460d-8af4-ffdbd600fc51");}
    },
    Speed {
      @Override public UUID id() {return UUID.fromString("6adad2d0-dd57-4d6b-932c-add277eb0cae");}
    }
  }

  /// Starting to move records the id of the created speed entity
  static EventType<Void, UUID> PacmanMovedForward = BasicEventType.of("Pacman moved forward", UUID.fromString("0908e790-6955-4d89-b11b-c552ea8c54ac"), DataType.none(), DataType.uuid());
  static EventType<Void, Void> PacmanMovedBackward = BasicEventType.of("Pacman moved backward", UUID.fromString("a6e631f6-9cf1-40cf-81eb-1480a05a5aae"));
  static EventType<Void, Void> PacmanTurnedLeft = BasicEventType.of("Pacman turned left", UUID.fromString("2c79f9f9-8650-4856-ab0a-d5f3e2a2677e"));
  static EventType<Void, Void> PacmanTurnedRight = BasicEventType.of("Pacman turned right", UUID.fromString("f17ab0f1-f370-4e55-a847-c22bc9996af2"));
  static EventType<Void, Void> PacmanStopped = BasicEventType.of("Pacman stopped", UUID.fromString("e5bf9178-bcf6-49f0-b149-8bfe1611615f"));
  static EventType<Void, Void> PacmanCrashed = BasicEventType.of("Pacman crashed", UUID.fromString("6c2a0d66-5c24-4637-bc7e-3d9d42ab9426"));

  static EventType<Void, Void> SpeedIncreased = BasicEventType.of("Speed increased", UUID.fromString("cd17fdf0-1f71-4ea8-9fd8-618a19f5e765"));
  static EventType<Void, Void> SpeedDecreased = BasicEventType.of("Speed decreased", UUID.fromString("6cd66f2c-9297-4783-a820-b4ac10b7299d"));

  private static UUID speedId(EventLog log) {
    return log.one(PacmanMovedForward);
  }

  private final static StateMachine eventListener = Init.stateMachine(
      Map.of(
          Pacman, Map.of(
              Begin, List.of(
                  onEvent(PacmanMovedForward).to(Moving)
                      .trigger(SpeedIncreased).on(Speed).identifiedBy(newEntityId())
                      .reversible(
                          assemble((log, _) -> speedId(log))
                              .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      )
                      .output(d -> d.t2().accepted().event().entityId())
              ),
              Stopped, List.of(
                  onEvent(PacmanMovedForward).to(Moving)
                      .trigger(SpeedIncreased).on(Speed).identifiedBy(newEntityId())
                      .reversible(
                          assemble((log, _) -> speedId(log))
                              .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      )
                      .output(d -> d.t2().accepted().event().entityId())
              ),
              Moving, List.of(
                  onEvent(PacmanTurnedLeft).toSelf().output(),
                  onEvent(PacmanTurnedRight).toSelf().output(),
                  onEvent(PacmanMovedBackward).toSelf()
                      .assemble(c -> speedId(c.log()))
                      .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      .reversible(
                          assemble((log, _) -> speedId(log))
                              .trigger(SpeedIncreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      )
                      .output(),
                  onEvent(PacmanMovedForward).toSelf()
                      .assemble(c -> speedId(c.log()))
                      .trigger(SpeedIncreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      .reversible(
                          assemble((log, _) -> speedId(log))
                              .trigger(SpeedDecreased).on(Speed).identifiedBy(EntitySelector::entityId)
                      )
                      .output(),
                  onEvent(PacmanStopped).to(Stopped).output(),
                  onEvent(PacmanCrashed).to(Dead).output()//,
                  //onEvent(EventsRolledBack).toSelf().assembleInput().output(d -> d)
              ),
              Dead, List.of()
          ),
          Speed, Map.of(
              Begin, List.of(
                  onEvent(SpeedIncreased).to(Slow).output()
              ),
              Zero, List.of(
                  onEvent(SpeedIncreased).to(Slow).output()
              ),
              Slow, List.of(
                  onEvent(SpeedIncreased).to(Normal).output(),
                  onEvent(SpeedDecreased).to(Zero).output()
              ),
              Normal, List.of(
                  onEvent(SpeedIncreased).to(Fast).output(),
                  onEvent(SpeedDecreased).to(Slow).output()
              ),
              Fast, List.of(
                  onEvent(SpeedDecreased).to(Normal).output()
              )

          )
      )
  );

  @Test
  public void rollbackOfOneEvent() {
    StepVerifier
        .create(eventListener.onEvent(trigger(PacmanMovedForward, Pacman))
            .flatMap(forwardEvent -> eventListener.onEvent(trigger(EventsRolledBack, Pacman, forwardEvent.entityId()), new Data(-1,
                    "test"))
                    .next()
                    .zipWhen(rollbackEvent -> eventListener.onStatus(entityId(rollbackEvent.entityId()), Pacman))
            )
        )
        .expectNextMatches(t -> t.getT1().type() == EventsRolledBack && t.getT1().eventNumber() == 2 && t.getT2() == Begin)
        .thenCancel().verify();
  }

  @Test
  public void statusOfUnknownEntityIsEmpty() {
    StepVerifier.create(eventListener.onStatus(entityId(UUID.randomUUID()), Pacman))
        .verifyComplete();
  }

  @Test
  public void eventOnUnknownEntityIsRejected() {
    UUID unknownId = UUID.randomUUID();
    StepVerifier.create(eventListener.onEvent(trigger(PacmanMovedForward, Pacman, unknownId)))
        .expectErrorMatches(t -> t instanceof RejectedEvent r
            && r.reason() == RejectedEvent.Reason.UnknownEntity
            && r.entityModel() == Pacman
            && r.entityId().equals(unknownId))
        .verify();
  }

  @Test
  public void rollbackOfTwoEvents() {
    StepVerifier
        .create(eventListener.onEvent(trigger(PacmanMovedForward, Pacman))
            .flatMap(forwardEvent -> eventListener.onEvent(trigger(PacmanMovedForward, Pacman, forwardEvent.entityId())))
            .flatMap(backwardEvent -> eventListener.onEvent(trigger(EventsRolledBack, Pacman, backwardEvent.entityId()), new Data(-2,
                "test")))
            .next()
            .zipWhen(rollbackEvent -> eventListener.onStatus(entityId(rollbackEvent.entityId()), Pacman))
        )
        .expectNextMatches(t -> t.getT1().type() == EventsRolledBack && t.getT1().eventNumber() == 3 && t.getT2() == Begin)
        .thenCancel().verify();
  }

  @Test
  public void rollbackOfTwoEventsWhenThereIsOnlyOne() {
    StepVerifier.create(
            eventListener.onEvent(trigger(PacmanMovedForward, Pacman))
                .flatMap(output -> eventListener.onEvent(trigger(EventsRolledBack, Pacman, output.entityId()), new Data(-2,
                    "test")))
        )
        .expectErrorMatches(t -> t instanceof RejectedEvent && t.getMessage().matches("\\[Events rolled back] on \\[Pacman]/.{36} rejected for state \\[Moving]: Can't rollback to event number -1"))
        .verify();
  }

  @Test
  public void rollbackToAFutureEvent() {
    StepVerifier.create(
            eventListener.onEvent(trigger(PacmanMovedForward, Pacman))
                .flatMap(output -> eventListener.onEvent(trigger(EventsRolledBack, Pacman, output.entityId()), new Data(2,
                    "test")))
        )
        .expectErrorMatches(t -> t instanceof RejectedEvent && t.getMessage().matches("\\[Events rolled back] on \\[Pacman]/.{36} rejected for state \\[Moving]: Can't rollback to event number 2"))
        .verify();
  }

}
