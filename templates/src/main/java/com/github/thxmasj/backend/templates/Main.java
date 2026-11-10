package com.github.thxmasj.backend.templates;

import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestDispatching;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.EntityModels.RequestRouting;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestDispatchingTransitions;
import static com.github.thxmasj.backend.http.inbox.TransitionModels.requestRoutingTransitions;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Payment;
import static com.github.thxmasj.backend.templates.cardpayment.Aggregate.Settlement;
import static com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.atLeastOnce;
import static com.github.thxmasj.backend.templates.cardpayment.DummyTransitions.atMostOnce;

import com.github.thxmasj.backend.core.Identifiable;
import com.github.thxmasj.backend.diagram.PlantUMLFormatter;
import com.github.thxmasj.backend.templates.cardpayment.DummyTransitions;
import java.io.IOException;
import java.util.List;
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
    System.out.println(new PlantUMLFormatter(
        Payment,
        DummyTransitions.payment().transitions()
    ).formatToImage("docs/images/"));
    System.out.println(new PlantUMLFormatter(
        Settlement,
        DummyTransitions.settlement().transitions()
    ).formatToImage("docs/images/"));
  }


}
