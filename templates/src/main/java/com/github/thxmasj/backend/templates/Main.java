package com.github.thxmasj.backend.templates;

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
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.backend.diagram.ERDiagramFormatter;
import com.github.thxmasj.backend.diagram.PlantUMLFormatter;
import com.github.thxmasj.backend.http.outbox.HttpOutboxRequest;
import com.github.thxmasj.backend.templates.cardpayment.Aggregate;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions;
import com.github.thxmasj.backend.templates.cardpayment.Identifiers;
import com.github.thxmasj.backend.templates.cardpayment.MerchantTransitions;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Main {

  static void main() throws IOException {
    var atLeastOnce = atLeastOnce(Identifiable.of("AtLeastOnce", UUID.randomUUID()));
    System.out.println(new PlantUMLFormatter(atLeastOnce, atLeastOnce.transitions()).formatToImage("docs/images/"));
    var atMostOnce = atMostOnce(
        Identifiable.of("AtMostOnce", UUID.randomUUID()),
        Identifiable.of("RollbackOnAccepted", UUID.randomUUID()),
        Identifiable.of("RollbackOnUnknown", UUID.randomUUID())
    );
    System.out.println(new PlantUMLFormatter(atMostOnce, atMostOnce.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestRouting, requestRoutingTransitions(List.of())).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(RequestDispatching, requestDispatchingTransitions(List.of(), List.of())).formatToImage("docs/images/"));
    var paymentTransitions = DummyTransitions.payment();
    var settlementTransitions = DummyTransitions.settlement();
    var payment = paymentTransitions.transitions();
    var settlement = settlementTransitions.transitions();
    System.out.println(new PlantUMLFormatter(Payment, payment).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Settlement, settlement).formatToImage("docs/images/"));
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
    System.out.println(new ERDiagramFormatter("com.github.thxmasj.backend.templates.cardpayment", cardPayments).compact().formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(Ids, Ids.transitions()).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(MessageIds, MessageIds.transitions()).formatToImage("docs/images/"));
  }


}
