package com.github.thxmasj.statemachine.templates.cardpayment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.thxmasj.statemachine.BeanRegistry;
import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.Event;
import com.github.thxmasj.statemachine.EventTrigger;
import com.github.thxmasj.statemachine.EventTrigger.EventSpec;
import com.github.thxmasj.statemachine.StateMachine;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.Tuples.Tuple3;
import com.github.thxmasj.statemachine.database.Client.Config;
import com.github.thxmasj.statemachine.database.jdbc.DataSourceBuilder;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage;
import com.github.thxmasj.statemachine.message.http.HttpRequestMessage.Method;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant.Location;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange.State.Dispatched;
import static com.github.thxmasj.statemachine.EntitySelector.newEntityId;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.http.HttpClientIdExtractor.fromBearerToken;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Merchant;

public class MerchantTest {

  private static StateMachine stateMachine;

  @BeforeAll
  public static void setup() {
    stateMachine = new StateMachine(
        null,
        _ -> Mono.empty(),
        new BeanRegistry() {
          @Override
          public <T> T getBean(Class<T> type) {
            return null;
          }
        },
        Map.of(
            Merchant, new MerchantTransitions(inboxExchange).transitions(),
            inboxExchange, inboxExchange.transitions()
        ),
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(),
        "Test",
        Clock.systemUTC(),
        new Logger("MerchantTest"),
        _ -> null
    );
  }

  @Test
  public void registerMerchant() throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    String body = mapper.writeValueAsString(new Merchant(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        "Test merchant",
        "Test merchant",
        new Location(null, null, "Oslo"),
        "5433",
        UUID.randomUUID().toString(),
        false
    ));
    HttpRequestMessage request = new HttpRequestMessage(Method.POST, URI.create("/merchants"), Map.of(), body);
    Event<?> result = stateMachine.onEvent(
        Optional.ofNullable(request.headerValue("x-correlation-id")).orElse(request.headerValue("tid")),
        new EventTrigger<>(
            new EventSpec<>(InboxExchange.Request, Function.identity()),
            List.of(EntitySelector.newEntityId()),
            inboxExchange,
            false
        ),
        request
    ).block(Duration.ofSeconds(3));

  }


  static InboxExchange inboxExchange = new InboxExchange() {

    interface HttpRequestLine {

      Pattern pattern();

      default boolean matches(String line) {
        return pattern().matcher(line).matches();
      }

      default <T> T from(String line, Function<Matcher, T> builder) {
        Matcher matcher = pattern().matcher(line);
        return matcher.find() ? builder.apply(matcher) : null;
      }

      default String from(String line, int captureGroup) {
        Matcher matcher = pattern().matcher(line);
        return matcher.find() ? matcher.group(captureGroup) : null;
      }
    }

    enum RequestLines implements HttpRequestLine {
      POST_merchant(Pattern.compile("POST .*/merchants"));
      private final Pattern pattern;

      RequestLines(Pattern pattern) {this.pattern = pattern;}

      @Override
      public Pattern pattern() {
        return pattern;
      }
    }

    private final static RequestType RegisterMerchant = new RequestType(
        "RegisterMerchant",
        UUID.fromString("a72b1371-e4d5-4361-a78d-97bd95c72008")
    );

    @Override
    protected List<TransitionModel<?, ?>> requestTransitions() {
      return List.of(
          onEvent(Request).to(Dispatched)
              .assembleInput()
              .when(d -> RequestLines.POST_merchant.matches(d.requestLine()))
              .then(
                  onEvent(RegisterMerchant).to(Dispatched)
                      .assemble((input, _) -> tuple(
                          input.data(),
                          input.data().body(PaymentEvent.Merchant.class)
                      ))
                      .newIdentifier(MessageId, d -> new MessageId("test", d.t2().id()))
                      .trigger(MerchantEvent.Create).with(Tuple2::t2).on(Merchant).identifiedBy(newEntityId())
                      .output(d -> d.t1().t1())
              )
              .when(_ -> true).then(invalidRequest(), _ -> "Request not mapped")
              .output(d -> d)
      );
    }

    @Override
    protected List<TransitionModel<?, ?>> responseTransitions() {
      return List.of();
    }
  };

  private static final DataSource dataSource = new DataSourceBuilder(databaseConfig(
      "testlogin",
      "Please_hide_me!"
  )).build();
  private static final DataSource migrationDataSource = new DataSourceBuilder(databaseConfig(
      "sa",
      "A_Str0ng_Required_Password"
  )).build();

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


}
