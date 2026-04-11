package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.BuiltinEventTypes.Rollback;
import static com.github.thxmasj.statemachine.EntitySelector.entityId;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.EventTrigger.trigger;
import static com.github.thxmasj.statemachine.RollbackTest.Entities.Pacman;
import static com.github.thxmasj.statemachine.RollbackTest.Entities.Speed;
import static com.github.thxmasj.statemachine.RollbackTest.PacmanStates.Dead;
import static com.github.thxmasj.statemachine.RollbackTest.PacmanStates.Moving;
import static com.github.thxmasj.statemachine.RollbackTest.PacmanStates.Stopped;
import static com.github.thxmasj.statemachine.RollbackTest.SpeedStates.Fast;
import static com.github.thxmasj.statemachine.RollbackTest.SpeedStates.Normal;
import static com.github.thxmasj.statemachine.RollbackTest.SpeedStates.Slow;
import static com.github.thxmasj.statemachine.RollbackTest.SpeedStates.Zero;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.assemble;

import com.github.thxmasj.statemachine.BasicEventType.Rollback.Data;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.Column;
import com.github.thxmasj.statemachine.database.mssql.SchemaNames.SecondaryIdModel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

public class RollbackTest {

  enum PacmanStates implements State {Stopped, Moving, Dead}
  enum SpeedStates implements State {Zero, Normal, Slow, Fast}

  enum Entities implements EntityModel {
    Pacman {
      @Override public UUID id() {return UUID.fromString("15f23da6-19be-460d-8af4-ffdbd600fc51");}
      @Override public State initialState() {return Stopped;}

      @Override
      public List<SecondaryIdModel<?>> secondaryIds() {
        return List.of(SpeedId);
      }
    },
    Speed {
      @Override public UUID id() {return UUID.fromString("6adad2d0-dd57-4d6b-932c-add277eb0cae");}
      @Override public State initialState() {return Zero;}
    }
  }

  static SecondaryIdModel<UUID> SpeedId = new SecondaryIdModel<>() {
    @Override
    public String name() {
      return "SpeedId";
    }

    @Override
    public List<Column> columns() {
      return List.of(new Column("SpeedId", "UNIQUEIDENTIFIER", id -> id));
    }

    @Override
    public SecondaryId<UUID> map(ResultSet resultSet) {
      try {
        return new SecondaryId<>(this, resultSet.getObject("SpeedId", UUID.class));
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  };

  static EventType<Void, Void> Forward = BasicEventType.of("Forward", UUID.fromString("0908e790-6955-4d89-b11b-c552ea8c54ac"));
  static EventType<Void, Void> Backward = BasicEventType.of("Backward", UUID.fromString("a6e631f6-9cf1-40cf-81eb-1480a05a5aae"));
  static EventType<Void, Void> Left = BasicEventType.of("Left", UUID.fromString("2c79f9f9-8650-4856-ab0a-d5f3e2a2677e"));
  static EventType<Void, Void> Right = BasicEventType.of("Right", UUID.fromString("f17ab0f1-f370-4e55-a847-c22bc9996af2"));
  static EventType<Void, Void> Stop = BasicEventType.of("Stop", UUID.fromString("e5bf9178-bcf6-49f0-b149-8bfe1611615f"));
  static EventType<Void, Void> Crash = BasicEventType.of("Crash", UUID.fromString("6c2a0d66-5c24-4637-bc7e-3d9d42ab9426"));

  static EventType<Void, Void> Increase = BasicEventType.of("Increase", UUID.fromString("cd17fdf0-1f71-4ea8-9fd8-618a19f5e765"));
  static EventType<Void, Void> Decrease = BasicEventType.of("Decrease", UUID.fromString("6cd66f2c-9297-4783-a820-b4ac10b7299d"));

  private final static StateMachine eventListener = Init.stateMachine(
      Map.of(
          Pacman, Map.of(
              Stopped, List.of(
                  onEvent(Forward).to(Moving)
                      .trigger(Increase).on(Speed).identifiedBy(newEntityId())
                      .newIdentifier(SpeedId, d -> d.t2().accepted().event().entityId())
                      .reversible(
                          assemble((log, _) -> log.id(SpeedId))
                              .trigger(Decrease).on(Speed).identifiedBy(entityId(d -> d))
                              .output()
                      )
                      .output()
              ),
              Moving, List.of(
                  onEvent(Left).toSelf().output(),
                  onEvent(Right).toSelf().output(),
                  onEvent(Backward).toSelf()
                      .assemble(c -> c.log().id(SpeedId))
                      .trigger(Decrease).on(Speed).identifiedBy(entityId(d -> d))
                      .reversible(
                          assemble((log, _) -> log.id(SpeedId))
                              .trigger(Increase).on(Speed).identifiedBy(entityId(d -> d))
                              .output()
                      )
                      .output(),
                  onEvent(Forward).toSelf()
                      .assemble(c -> c.log().id(SpeedId))
                      .trigger(Increase).on(Speed).identifiedBy(entityId(d -> d))
                      .reversible(
                          assemble((log, _) -> log.id(SpeedId))
                              .trigger(Decrease).on(Speed).identifiedBy(entityId(d -> d))
                              .output()
                      )
                      .output(),
                  onEvent(Stop).to(Stopped).output(),
                  onEvent(Crash).to(Dead).output(),
                  onEvent(Rollback).toSelf().assembleInput().output(d -> d)
              ),
              Dead, List.of()
          ),
          Speed, Map.of(
              Zero, List.of(
                  onEvent(Increase).to(Slow).output()
              ),
              Slow, List.of(
                  onEvent(Increase).to(Normal).output(),
                  onEvent(Decrease).to(Zero).output()
              ),
              Normal, List.of(
                  onEvent(Increase).to(Fast).output(),
                  onEvent(Decrease).to(Slow).output()
              ),
              Fast, List.of(
                  onEvent(Decrease).to(Normal).output()
              )

          )
      ),
      null
  );

  @Test
  public void rollbackOfOneEvent() {
    StepVerifier
        .create(eventListener.onEvent(trigger(Forward, Pacman))
            .flatMap(forwardEvent -> eventListener.onEvent(
                        trigger(Rollback, Pacman, forwardEvent.entityId()),
                        new Data(-1, "test")
                    )
                    .next()
                    .zipWhen(rollbackEvent -> eventListener.onStatus(entityId(_ -> rollbackEvent.entityId()), Pacman))
            )
        )
        .expectNextMatches(t -> t.getT1().type() == Rollback && t.getT1().eventNumber() == 2 && t.getT2() == Stopped)
        .thenCancel().verify();
  }

  @Test
  public void rollbackOfTwoEvents() {
    StepVerifier
        .create(eventListener.onEvent(trigger(Forward, Pacman))
            .flatMap(forwardEvent -> eventListener.onEvent(trigger(Forward, Pacman, forwardEvent.entityId())))
            .flatMap(backwardEvent -> eventListener.onEvent(trigger(Rollback, Pacman, backwardEvent.entityId()), new Data(-2, "test")))
            .next()
            .zipWhen(rollbackEvent -> eventListener.onStatus(entityId(rollbackEvent.entityId()), Pacman))
        )
        .expectNextMatches(t -> t.getT1().type() == Rollback && t.getT1().eventNumber() == 3 && t.getT2() == Stopped)
        .thenCancel().verify();
  }

  @Test
  public void rollbackOfTwoEventsWhenThereIsOnlyOne() {
    StepVerifier.create(
            eventListener.onEvent(trigger(Forward, Pacman))
                .flatMap(output -> eventListener.onEvent(
                    trigger(Rollback, Pacman, output.entityId()),
                    new Data(-2, "test")
                ))
        )
        .expectErrorMessage("Rollback on Pacman not allowed for Moving: Can't rollback to event number -1")
        .verify();
  }

  @Test
  public void rollbackToAFutureEvent() {
    StepVerifier.create(
            eventListener.onEvent(trigger(Forward, Pacman))
                .flatMap(output -> eventListener.onEvent(
                    trigger(Rollback, Pacman, output.entityId()),
                    new Data(2, "test")
                ))
        )
        .expectErrorMessage("Rollback on Pacman not allowed for Moving: Can't rollback to event number 2")
        .verify();
  }

}
