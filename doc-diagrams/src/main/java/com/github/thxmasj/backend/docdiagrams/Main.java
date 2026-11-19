package com.github.thxmasj.backend.docdiagrams;

import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestRouting;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;

import com.github.thxmasj.backend.backoffice.Backoffice;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.diagram.ERDiagramFormatter;
import com.github.thxmasj.backend.diagram.GraphvizFormatter;
import com.github.thxmasj.backend.diagram.PlantUMLFormatter;
import com.github.thxmasj.backend.helloworld.Greetings;
import com.github.thxmasj.backend.http.inbox.HttpInbox;
import com.github.thxmasj.backend.identityprovider.Authorizations;
import com.github.thxmasj.backend.identityprovider.Clients;
import com.github.thxmasj.backend.identityprovider.Inbox;
import com.github.thxmasj.backend.identityprovider.Tokens;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/// Generates the diagrams of the documentation site into `docs/images`. Run from the repository root.
public class Main {

  private static final String directory = "docs/images/";

  static void main() throws IOException {
    httpOutbox();
    httpInbox();
    cardPayments();
    identityProvider();
    helloWorld();
    backoffice();
  }

  /// The examples of the HTTP outbox page, each drawn under its code
  private static void httpOutbox() throws IOException {
    var name = "com.github.thxmasj.backend.http.outbox";
    var notification = DocExamples.MerchantNotification;
    stateDiagram(name + ".MerchantNotification", notification);
    var authorisation = DocExamples.Authorisation;
    stateDiagram(name + ".Authorisation", authorisation);
  }

  /// The examples of the HTTP inbox page, each drawn under its code, and how a rollback finds the request and the
  /// events it rolls back
  private static void httpInbox() throws IOException {
    var name = "com.github.thxmasj.backend.http.inbox";
    var placeOrder = DocExamples.PlaceOrder;
    stateDiagram(name + ".PlaceOrder", placeOrder);
    var enrolmentResult = DocExamples.EnrolmentResultReceived;
    stateDiagram(name + ".EnrolmentResultReceived", enrolmentResult);
    var withRollback = DocExamples.PlaceOrderWithRollback;
    stateDiagram(name + ".PlaceOrderWithRollback", withRollback);
    stateDiagram(name + ".RequestRouting", HttpInbox.of(List.of(withRollback), List.of()).requestRouting());
    stateDiagram(MessageIds);
    graphviz("http-inbox-rollback.dot", name + ".Rollback");
  }

  private static void cardPayments() throws IOException {
    var name = "com.github.thxmasj.backend.templates.cardpayment";
    var cardPayments = DummyTransitions.cardPayments();
    stateDiagram(name + ".Payment", cardPayments.payment());
    stateDiagram(name + ".Settlement", cardPayments.settlement());
    erDiagram(name, cardPayments.entityModels());
    stateDiagram(Ids);
  }

  private static void identityProvider() throws IOException {
    // The token lifetime is configuration, and doesn't change the model
    var tokenLifetime = Duration.ofMinutes(5);
    var name = "com.github.thxmasj.backend.identityprovider";
    stateDiagram(name + ".Client", Clients.Client);
    stateDiagram(name + ".ClientRegistration", Clients.ClientRegistration);
    stateDiagram(name + ".TokenRequest", Tokens.tokenRequest(tokenLifetime));
    stateDiagram(name + ".Authorization", Authorizations.Authorization);
    erDiagram(name, Inbox.inbox(tokenLifetime).entityModels());
  }

  private static void helloWorld() throws IOException {
    var name = "com.github.thxmasj.backend.helloworld";
    stateDiagram(name + ".Greeting", Greetings.Greeting);
    stateDiagram(name + ".GreetingRequest", Greetings.GreetingRequest);
    erDiagram(name, Greetings.Inbox.entityModels());
  }

  private static void backoffice() throws IOException {
    // The model doesn't depend on the configuration, which is only used when a request is handled
    var config = new Backoffice.Config(URI.create("https://backoffice.example.com"), URI.create("https://login.example.com"), "backoffice", null, "openid", "roles", Set.of("backoffice"), Duration.ofHours(8));
    var backoffice = new Backoffice("Example", config, null, "example", List.of());
    stateDiagram("com.github.thxmasj.backend.backoffice.BackofficeSession", backoffice.sessionModel());
  }

  private static void stateDiagram(EntityModel model) throws IOException {
    System.out.println(new PlantUMLFormatter(model).formatToImage(directory));
  }

  /// For the entity models of the apps, which are created with `EntityModel.of` and so would be named after the
  /// package of the core library
  private static void stateDiagram(String fileName, EntityModel model) throws IOException {
    System.out.println(new PlantUMLFormatter(model).formatToImage(new File(directory, fileName + ".svg")));
  }

  /// A diagram drawn by hand in Graphviz, from a resource next to this class
  private static void graphviz(String resource, String fileName) throws IOException {
    try (var source = Main.class.getResourceAsStream(resource)) {
      if (source == null)
        throw new IOException("No resource " + resource);
      var formatter = new GraphvizFormatter(new String(source.readAllBytes(), StandardCharsets.UTF_8));
      System.out.println(formatter.formatToImage(new File(directory, fileName + ".svg")));
    }
  }

  private static void erDiagram(String name, List<EntityModel> entityModels) throws IOException {
    System.out.println(new ERDiagramFormatter(name, entityModels).compact().formatToImage(directory));
  }

}
