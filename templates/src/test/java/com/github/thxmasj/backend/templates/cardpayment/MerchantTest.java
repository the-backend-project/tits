package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Merchant;

import com.github.thxmasj.backend.core.StateMachine;
import com.github.thxmasj.backend.core.test.Init;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import reactor.core.publisher.Mono;

public class MerchantTest {

  private static StateMachine stateMachine;

  @BeforeAll
  public static void setup() {
    stateMachine = new StateMachine(
        _ -> Mono.empty(),
        Map.of(
            Merchant, new MerchantTransitions().transitions()
//            ,
//            inboxExchange, inboxExchange.transitions()
        ),
        dataSource,
        migrationDataSource,
        UUID.randomUUID().toString(),
        "Test",
        Clock.systemUTC(),
        new Logger("MerchantTest")
    );
  }

//  @Test
//  public void registerMerchant() throws JsonProcessingException {
//    ObjectMapper mapper = new ObjectMapper();
//    String body = mapper.writeValueAsString(new Merchant(
//        UUID.randomUUID().toString(),
//        UUID.randomUUID().toString(),
//        "Test merchant",
//        new Location(null, null, "Oslo"),
//        "5433",
//        UUID.randomUUID().toString(),
//        false
//    ));
//    HttpRequestMessage request = new HttpRequestMessage(Method.POST, URI.create("/merchants"), Map.of(), body);
//    Event<?> result = stateMachine.onEvent(
//        Optional.ofNullable(request.headerValue("x-correlation-id")).orElse(request.headerValue("tid")),
//        new EventTrigger<>(
//            new EventSpec<>(InboxExchange.Request, Function.identity()),
//            List.of(EntitySelector.newEntityId()),
//            inboxExchange,
//            false
//        ),
//        request
//    ).skip(1).next().block(Duration.ofSeconds(3));
//
//  }
//
//
//  static InboxExchange inboxExchange = new InboxExchange() {
//
//    interface HttpRequestLine {
//
//      Pattern pattern();
//
//      default boolean matches(String line) {
//        return pattern().matcher(line).matches();
//      }
//
//      default <T> T from(String line, Function<Matcher, T> builder) {
//        Matcher matcher = pattern().matcher(line);
//        return matcher.find() ? builder.apply(matcher) : null;
//      }
//
//      default String from(String line, int captureGroup) {
//        Matcher matcher = pattern().matcher(line);
//        return matcher.find() ? matcher.group(captureGroup) : null;
//      }
//    }
//
//    enum RequestLines implements HttpRequestLine {
//      POST_merchant(Pattern.compile("POST .*/merchants"));
//      private final Pattern pattern;
//
//      RequestLines(Pattern pattern) {this.pattern = pattern;}
//
//      @Override
//      public Pattern pattern() {
//        return pattern;
//      }
//    }
//
//    private final static RequestType RegisterMerchant = new RequestType(
//        "RegisterMerchant",
//        UUID.fromString("a72b1371-e4d5-4361-a78d-97bd95c72008")
//    );
//
//    @Override
//    protected Map<Predicate<HttpRequestMessage>, Alternative<HttpRequestMessage, ?, ?>> routes() {
//      return Map.of(
//          d -> RequestLines.POST_merchant.matches(d.requestLine()),
//          then(
//              onEvent(RegisterMerchant).to(Requested)
//                  .assemble((input, _) -> tuple(
//                      input,
//                      input.body(PaymentEvent.Merchant.class)
//                  ))
//                  .newIdentifier(BuiltinEntities.MessageId, d -> new MessageId("test", d.t2().id()))
//                  .trigger(MerchantEvent.Create).with(d -> d.t1().t2()).on(Merchant).identifiedBy(newEntityId())
//                  .output(d -> d.t1().t1().t1())
//          )
//      );
//    }
//
//    @Override
//    protected List<TransitionModel<?, ?>> responseTransitions() {
//      return List.of();
//    }
//  };

  private static final DataSource dataSource = Init.dataSource();
  private static final DataSource migrationDataSource = Init.migrationDataSource();


}
