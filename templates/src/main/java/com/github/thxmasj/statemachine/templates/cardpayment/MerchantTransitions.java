package com.github.thxmasj.statemachine.templates.cardpayment;

import static com.github.thxmasj.statemachine.BuiltinEntities.CompleteInvalidRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.CompleteRequest;
import static com.github.thxmasj.statemachine.BuiltinEntities.Models.RequestDispatching;
import static com.github.thxmasj.statemachine.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.statemachine.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.statemachine.Tuples.tuple;
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

import com.github.thxmasj.statemachine.BuiltinEventTypes;
import com.github.thxmasj.statemachine.State;
import com.github.thxmasj.statemachine.TransitionModelBuilder.TransitionModel;
import com.github.thxmasj.statemachine.templates.cardpayment.MerchantEvent.MerchantUpdate;
import com.github.thxmasj.statemachine.templates.cardpayment.PaymentEvent.Merchant;
import java.util.List;
import java.util.Map;

public class MerchantTransitions {

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            onEvent(Create).to(Active)
                .assemble(c -> tuple(c.input(), c.eventReference()))
                .newIdentifier(MerchantId, d -> d.t1().id())
                .trigger(CompleteRequest).with(d -> tuple("", d.t1().t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1().t1()),
            onEvent(BuiltinEventTypes.SecondaryIdAlreadyExists).toSelf()
                .assemble(c -> tuple(c.input().t2(), c.eventReference()))
                .trigger(CompleteInvalidRequest).with(d -> tuple(d.t1().data().toString(), d.t2())).on(RequestDispatching).identifiedBy(entityIdFromSession())
                .output()
        ),
        Active, List.of(
            onEvent(Suspend).to(Suspended).output(),
            onEvent(Delete).to(Deleted).output(),
            onEvent(Update).toSelf().assemble((input, log) -> merge(log.last(Merchant.class), input)).output(d -> d),
            onEvent(Get).toSelf().assemble((_, log) -> log.last(Merchant.class)).output(d -> d)
        ),
        Suspended, List.of(
            onEvent(Resume).to(Active).output(),
            onEvent(Update).toSelf().assemble((input, log) -> merge(log.last(Merchant.class), input)).output(d -> d),
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

}
