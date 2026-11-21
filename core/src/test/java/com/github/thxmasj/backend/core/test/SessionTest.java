package com.github.thxmasj.backend.core.test;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EventTrigger.trigger;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.backend.core.Action;
import com.github.thxmasj.backend.core.BasicEventType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Event;
import com.github.thxmasj.backend.core.EventType;
import com.github.thxmasj.backend.core.InputEvent;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.TransitionModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/// A session is the transactions an event leads to through actions. A transaction processing the outcome of an
/// action finds the entity of the session, and emits its events to the one waiting for them. See docs/sessions.md.
@Tag("database")
public class SessionTest {

  enum RequestStates implements State {Waiting, Answered}

  enum WorkerStates implements State {Once, Twice1, Twice2, Done}

  static EntityModel Request = EntityModel.of("Request", UUID.fromString("5e55e0a1-1a2b-4c3d-8e4f-000000000001"), SessionTest::requestTransitions);
  static EntityModel Worker = EntityModel.of("Worker", UUID.fromString("5e55e0a1-1a2b-4c3d-8e4f-000000000002"), SessionTest::workerTransitions);

  static EventType<Void, Void> StartedOnce = event("Started once", 3);
  static EventType<Void, Void> StartedTwice = event("Started twice", 4);
  static EventType<Void, Void> Answered = event("Answered", 5);
  static EventType<Void, Void> HopOnce = event("Hop once", 6);
  static EventType<Void, Void> HopTwice = event("Hop twice", 7);
  static EventType<Void, Void> NextHop = event("Next hop", 8);
  static EventType<Void, Void> HopsDone = event("Hops done", 9);

  static Action<Void> next = action("Next", NextHop);
  static Action<Void> done = action("Done", HopsDone);

  private static EventType<Void, Void> event(String name, int n) {
    return BasicEventType.of(name, UUID.fromString("5e55e0a1-1a2b-4c3d-8e4f-%012d".formatted(n)));
  }

  /// An action whose outcome is the event, as of an outbox request getting its response
  private static Action<Void> action(String name, EventType<Void, Void> outcome) {
    return new Action<>() {
      @Override public String name() {return name;}
      @Override public Mono<InputEvent<?>> execute(Void data) {return Mono.just(new InputEvent<>(outcome, null));}
    };
  }

  private static Map<State, List<TransitionModel<?, ?>>> requestTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(StartedOnce).to(RequestStates.Waiting)
                .trigger(HopOnce).with(_ -> null).on(Worker).identifiedBy(newEntityId())
                .output(),
            onEvent(StartedTwice).to(RequestStates.Waiting)
                .trigger(HopTwice).with(_ -> null).on(Worker).identifiedBy(newEntityId())
                .output()
        ),
        RequestStates.Waiting, List.of(onEvent(Answered).to(RequestStates.Answered).output()),
        RequestStates.Answered, List.of()
    );
  }

  private static Map<State, List<TransitionModel<?, ?>>> workerTransitions() {
    return Map.<State, List<TransitionModel<?, ?>>>of(
        Begin, List.of(
            onEvent(HopOnce).to(WorkerStates.Once).assemble(c -> c.input()).trigger(done).with(_ -> null).output(),
            onEvent(HopTwice).to(WorkerStates.Twice1).assemble(c -> c.input()).trigger(next).with(_ -> null).output()
        ),
        WorkerStates.Twice1, List.of(onEvent(NextHop).to(WorkerStates.Twice2).assemble(c -> c.input()).trigger(done).with(_ -> null).output()),
        WorkerStates.Once, List.of(answer()),
        WorkerStates.Twice2, List.of(answer()),
        WorkerStates.Done, List.of()
    );
  }

  /// Answers the request of the session, not knowing which one it is
  private static TransitionModel<?, ?> answer() {
    return onEvent(HopsDone).to(WorkerStates.Done)
        .trigger(Answered).with(_ -> null).on(Request).identifiedBy(entityIdFromSession())
        .output();
  }

  private StateMachine stateMachine() {
    return Init.stateMachine(UUID.randomUUID().toString(), List.of(Request, Worker));
  }

  private static List<String> eventsOfRequest(StateMachine machine, EventType<Void, Void> started) {
    return machine.onEvent(trigger(started, Request))
        .take(2)
        .take(Duration.ofSeconds(10))
        .map(Event::typeName)
        .collectList()
        .block();
  }

  @Test
  public void entityOfSessionIsFoundOneActionBack() {
    assertEquals(List.of(StartedOnce.name(), Answered.name()), eventsOfRequest(stateMachine(), StartedOnce));
  }

  @Test
  @Disabled("The session reaches one action back, see https://github.com/the-backend-project/backend/issues/308")
  public void entityOfSessionIsFoundTwoActionsBack() {
    assertEquals(List.of(StartedTwice.name(), Answered.name()), eventsOfRequest(stateMachine(), StartedTwice));
  }

}
