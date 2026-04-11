package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
import static com.github.thxmasj.statemachine.templates.cardpayment.Aggregate.Merchant;
import static com.github.thxmasj.statemachine.templates.cardpayment.Identifiers.MerchantId;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Create;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Delete;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Get;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Resume;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Suspend;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.Update;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantState.Active;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantState.Begin;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantState.Deleted;
import static com.github.thxmasj.statemachine.templates.cardpayment.MerchantState.Suspended;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.MerchantUpdate;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MerchantTransitions {

  private final InboxExchange inboxExchange;

  public MerchantTransitions(InboxExchange inboxExchange) {this.inboxExchange = inboxExchange;}

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            onEvent(Create).to(Active)
                .assemble(c -> tuple(c.input().data(), c.log().entityId()))
                .newIdentifier(MerchantId, d -> d.t1().id())
                .trigger(InboxExchange.AcceptedRequest).with(d -> d.t1().t2()).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1()),
            onEvent(BuiltinEventTypes.SecondaryIdAlreadyExists).toSelf()
                .assembleInput()
                .trigger(InboxExchange.InvalidRequest).with(d -> d.t2().data().toString()).on(inboxExchange).identifiedBy(entityIdFromSession())
                .output()
        ),
        Active, List.of(
            onEvent(Suspend).to(Suspended).output(),
            onEvent(Delete).to(Deleted).output(),
            onEvent(Update).toSelf().assemble((input, log) -> merge(log.last(Merchant.class), input.data())).output(d -> d),
            onEvent(Get).toSelf().assemble((_, log) -> log.last(Merchant.class)).output(d -> d)
        ),
        Suspended, List.of(
            onEvent(Resume).to(Active).output(),
            onEvent(Update).toSelf().assemble((input, log) -> merge(log.last(Merchant.class), input.data())).output(d -> d),
            onEvent(Delete).to(Deleted).output()
        ),
        Deleted, List.of()
    );
  }

  private Merchant merge(Merchant merchant, MerchantUpdate merchantUpdate) {
    return new Merchant(
        merchant.aggregatorId(),
        merchant.id(),
        ofNullable(merchantUpdate.merchantDisplayName()).orElse(merchant.displayName()),
        ofNullable(merchantUpdate.merchantLocation()).orElse(merchant.location()),
        ofNullable(merchantUpdate.merchantCategoryCode()).orElse(merchant.categoryCode()),
        merchant.acquirerId(),
        merchant.superMerchant()
    );
  }

  static void main() throws IOException {
    System.out.println(new PlantUMLFormatter(Merchant, new MerchantTransitions(null).transitions()).formatToImage("docs/images"));
  }

}
