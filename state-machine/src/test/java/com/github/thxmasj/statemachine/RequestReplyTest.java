package com.github.thxmasj.statemachine;

import static com.github.thxmasj.statemachine.EventTriggerBuilder.event;
import static com.github.thxmasj.statemachine.IncomingRequestModel.validator;
import static com.github.thxmasj.statemachine.IncomingRequestModelBuilder.fromRequestLine;
import static com.github.thxmasj.statemachine.RequestReplyTest.Entities.Lamp;
import static com.github.thxmasj.statemachine.RequestReplyTest.Events.SwitchOff;
import static com.github.thxmasj.statemachine.RequestReplyTest.Events.SwitchOn;
import static com.github.thxmasj.statemachine.RequestReplyTest.Events.Toggle;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.Off;
import static com.github.thxmasj.statemachine.RequestReplyTest.States.On;
import static com.github.thxmasj.statemachine.StateMachine.ProcessResult.Status.Accepted;
import static com.github.thxmasj.statemachine.TransitionModel.Builder.from;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.thxmasj.statemachine.StateMachine.ProcessResult;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@Disabled
public class RequestReplyTest {

  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig("testlogin", "Please_hide_me!")).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig("sa", "A_Str0ng_Required_Password")).build();

  private static Config databaseConfig(String username, String password) {
    return new Config(
        "localhost",
        11433,
        "work",
        username,
        password,
        true,
        10,
        Duration.ofSeconds(10)
    );
  }

  enum States implements State {On, Off}

  enum Events implements EventType {
    Toggle(1001),
    SwitchOn(1002),
    SwitchOff(1003),
    ;

    private final int id;

    Events(int id) {this.id = id;}

    @Override
    public int id() {
      return id;
    }

  }

  enum Entities implements EntityModel {
    Lamp {
      @Override
      public UUID id() {
        return UUID.fromString("59d3158c-7b2a-4312-a293-325858c2673f");
      }

      @Override
      public List<EventType> eventTypes() {
        return List.of(Events.values());
      }

      @Override
      public State initialState() {
        return Off;
      }

      @Override
      public List<TransitionModel<?>> transitions() {
        return List.of(
            from(On).to(Off).onEvent(Toggle).build(),
            from(On).to(Off).onEvent(SwitchOff).build(),
            from(Off).to(On).onEvent(Toggle).response((_, _, _, _, _) -> Mono.just("Light is on!")),
            from(Off).to(On).onEvent(SwitchOn).build()
        );
      }
    }
  }

  private static StateMachine stateMachine;

  @BeforeAll
  public static void setUp() {
    stateMachine = new StateMachine(
        message -> switch (message.requestLine()) {
          case String l when l.matches("PUT .*/lamps/.*") -> validator(new IncomingRequestValidator<Void>() {})
              .trigger(event(Toggle)
                  .onEntity(Lamp)
                  .identifiedBy(EntitySelectorBuilder.entityId(fromRequestLine(message, "PUT .*/lamps/(.*)", 1)).createIfNotExists())
              )
              .clientId("system")
              .derivedMessageId();
          default -> throw new IllegalStateException("Unexpected value: " + message);
        },
        _ -> Mono.empty(),
        new BeanRegistry() {
          @Override
          public <T> T getBean(Class<T> type) {
            return null;
          }
        },
        List.of(Entities.values()),
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(),
        "Test",
        Clock.systemUTC(),
        new Logger("RequestReplyTest"),
        false,
        null
    );
  }

  @Test
  public void toggle() {
    String lampId = UUID.randomUUID().toString();
    ProcessResult result = stateMachine.processRequest("PUT /lamps/" + lampId).block(Duration.ofSeconds(1));
    assertEquals(Accepted, result.status());
    assertEquals("Light is on!", result.responseMessage());
  }


}
