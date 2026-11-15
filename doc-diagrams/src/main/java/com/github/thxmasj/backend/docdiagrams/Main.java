package com.github.thxmasj.backend.docdiagrams;

import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.MessageIds;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.atLeastOnce;
import static com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.atMostOnce;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;

import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.diagram.ERDiagramFormatter;
import com.github.thxmasj.backend.diagram.PlantUMLFormatter;
import com.github.thxmasj.backend.helloworld.Greetings;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.identityprovider.Clients;
import com.github.thxmasj.backend.identityprovider.Inbox;
import com.github.thxmasj.backend.identityprovider.Tokens;
import com.github.thxmasj.backend.templates.cardpayment.Aggregate;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions;
import com.github.thxmasj.backend.templates.cardpayment.Identifiers;
import com.github.thxmasj.backend.templates.cardpayment.MerchantTransitions;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Generates the diagrams of the documentation site into `docs/images`. Run from the repository root.
public class Main {

  private static final String directory = "docs/images/";

  static void main() throws IOException {
    httpOutbox();
    httpInbox();
    cardPayments();
    identityProvider();
    helloWorld();
  }

  private static void httpOutbox() throws IOException {
    var atLeastOnce = atLeastOnce(Identifiable.of("AtLeastOnce", UUID.randomUUID()));
    stateDiagram(atLeastOnce, atLeastOnce.transitions());
    var atMostOnce = atMostOnce(
        Identifiable.of("AtMostOnce", UUID.randomUUID()),
        Identifiable.of("RollbackOnAccepted", UUID.randomUUID()),
        Identifiable.of("RollbackOnUnknown", UUID.randomUUID())
    );
    stateDiagram(atMostOnce, atMostOnce.transitions());
  }

  private static void httpInbox() throws IOException {
    stateDiagram(RequestRouting, requestRoutingTransitions(List.of()));
    stateDiagram(RequestDispatching, requestDispatchingTransitions(List.of(), List.of()));
    stateDiagram(MessageIds, MessageIds.transitions());
  }

  private static void cardPayments() throws IOException {
    var paymentTransitions = DummyTransitions.payment();
    var settlementTransitions = DummyTransitions.settlement();
    var payment = paymentTransitions.transitions();
    var settlement = settlementTransitions.transitions();
    stateDiagram(Payment, payment);
    stateDiagram(Settlement, settlement);
    Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> cardPayments = new HashMap<>();
    cardPayments.put(Aggregate.Merchant, new MerchantTransitions().transitions());
    cardPayments.put(Payment, payment);
    cardPayments.put(Settlement, settlement);
    cardPayments.put(RequestRouting, requestRoutingTransitions(List.of()));
    cardPayments.put(RequestDispatching, requestDispatchingTransitions(List.of(), List.of()));
    cardPayments.put(MessageIds, MessageIds.transitions());
    cardPayments.put(Ids, Ids.transitions());
    cardPayments.put(Identifiers.Index, Identifiers.Index.transitions());
    cardPayments.put(HttpOutboxRequest.Index, HttpOutboxRequest.Index.transitions());
    cardPayments.putAll(paymentTransitions.outboxTransitions());
    cardPayments.putAll(settlementTransitions.outboxTransitions());
    erDiagram("com.github.thxmasj.backend.templates.cardpayment", cardPayments);
    stateDiagram(Ids, Ids.transitions());
  }

  private static void identityProvider() throws IOException {
    // The token lifetime is configuration, and doesn't change the model
    var transitions = Inbox.transitions(Duration.ofMinutes(5));
    var name = "com.github.thxmasj.backend.identityprovider";
    stateDiagram(name + ".Client", Clients.Client, transitions);
    stateDiagram(name + ".ClientRegistration", Clients.ClientRegistration, transitions);
    stateDiagram(name + ".TokenRequest", Tokens.TokenRequest, transitions);
    erDiagram(name, transitions);
  }

  private static void helloWorld() throws IOException {
    var transitions = Greetings.transitions();
    var name = "com.github.thxmasj.backend.helloworld";
    stateDiagram(name + ".Greeting", Greetings.Greeting, transitions);
    erDiagram(name, transitions);
  }

  private static void stateDiagram(EntityModel model, Map<State, List<TransitionModel<?, ?>>> transitions) throws IOException {
    System.out.println(new PlantUMLFormatter(model, transitions).formatToImage(directory));
  }

  /// For the entity models of the apps, which are created with `EntityModel.of` and so would be named after the
  /// package of the core library
  private static void stateDiagram(
      String fileName,
      EntityModel model,
      Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions
  ) throws IOException {
    System.out.println(new PlantUMLFormatter(model, transitions.get(model)).formatToImage(new File(directory, fileName + ".svg")));
  }

  private static void erDiagram(String name, Map<EntityModel, Map<State, List<TransitionModel<?, ?>>>> transitions) throws IOException {
    System.out.println(new ERDiagramFormatter(name, transitions).compact().formatToImage(directory));
  }

}
