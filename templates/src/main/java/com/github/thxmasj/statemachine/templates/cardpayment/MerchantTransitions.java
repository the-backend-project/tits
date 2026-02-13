package com.github.thxmasj.statemachine.templates.cardpayment;

import com.github.thxmasj.statemachine.BuiltinEntities.InboxExchange;
import com.github.thxmasj.statemachine.EntitySelector;
import com.github.thxmasj.statemachine.PlantUMLFormatter;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.Tuples.Tuple2;
import com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.MerchantUpdate;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
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

public class MerchantTransitions {

  private final InboxExchange inboxExchange;

  public MerchantTransitions(InboxExchange inboxExchange) {this.inboxExchange = inboxExchange;}

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            onEvent(Create).to(Active)
                .assembleInput()
                .newIdentifier(MerchantId, PaymentEvent.Merchant::id)
                .trigger(InboxExchange.AcceptedRequest).with(_ -> "").on(inboxExchange).identifiedBy(entityIdFromSession())
                .output(Tuple2::t1)
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
        ofNullable(merchantUpdate.merchantName()).orElse(merchant.name()),
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
