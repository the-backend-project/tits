package com.github.thxmasj.backend.templates.cardpayment;

import static com.github.thxmasj.backend.core.EntityModel.Begin;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.InvalidRequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.RequestCompleted;
import static com.github.thxmasj.backend.http.inbox.HttpInbox.Request;
import static com.github.thxmasj.backend.core.EntitySelector.entityIdFromSession;
import static com.github.thxmasj.backend.core.EntitySelector.newEntityId;
import static com.github.thxmasj.backend.core.EntitySelector.CreationMode.CreateIfNotExists;
import static com.github.thxmasj.backend.core.EntitySelector.entityId;
import static com.github.thxmasj.backend.core.TransitionModelBuilder.WithEvent.onEvent;
import static com.github.thxmasj.backend.core.Tuples.tuple;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.Ids;
import static com.github.thxmasj.backend.templates.cardpayment.Identifiers.MerchantIdClaimed;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.DuplicateMerchantIdDetected;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantActivated;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantCreated;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantDeleted;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantLookedUp;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantResumed;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantSuspended;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantUpdated;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantState.Active;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantState.Deleted;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantState.Rejected;
import static com.github.thxmasj.backend.templates.cardpayment.MerchantState.Suspended;
import static com.github.thxmasj.backend.templates.cardpayment.SettlementEvent.InitialSettlementOpened;
import static java.util.Optional.ofNullable;

import com.github.thxmasj.backend.core.DataType;
import com.github.thxmasj.backend.core.EntityModel;
import com.github.thxmasj.backend.core.State;
import com.github.thxmasj.backend.core.TransitionModelBuilder.TransitionContext;
import com.github.thxmasj.backend.core.TransitionModel;
import com.github.thxmasj.backend.templates.cardpayment.MerchantEvent.MerchantUpdate;
import com.github.thxmasj.backend.templates.cardpayment.PaymentEvent.Merchant;
import java.util.List;
import java.util.Map;

public class MerchantTransitions {

  private final EntityModel settlement;

  /// @param settlement the model of the settlements of the merchants
  MerchantTransitions(EntityModel settlement) {
    this.settlement = settlement;
  }

  public Map<State, List<TransitionModel<?, ?>>> transitions() {
    return Map.of(
        Begin, List.of(
            // The merchant id is claimed for the merchant. If another merchant has claimed it already, the merchant is
            // rejected, and gets no settlement.
            onEvent(MerchantCreated).to(Active)
                .assemble(c -> tuple(c.input(), c.entityId()))
                .trigger(MerchantIdClaimed).with(d -> d.t1().id()).on(Ids).identifiedBy(d -> entityId(d.t2(), CreateIfNotExists))
                .when(d -> d.t2().isIdExists()).then(
                    onEvent(DuplicateMerchantIdDetected).to(Rejected)
                        .assemble(c -> tuple(c.input(), c.eventReference()))
                        .trigger(InvalidRequestCompleted)
                        .with(d -> tuple("Merchant id " + d.t1() + " is taken", d.t2()))
                        .on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1()),
                    d -> d.t1().t1().id()
                )
                .otherwise(
                    onEvent(MerchantActivated).to(Active)
                        .assemble(c -> tuple(c.input(), c.eventReference()))
                        .trigger(InitialSettlementOpened).with(d -> new MerchantId(d.t1().id())).on(settlement).identifiedBy(newEntityId())
                        .trigger(RequestCompleted).with(d -> tuple("", d.t1().t2())).on(Request).identifiedBy(entityIdFromSession())
                        .output(d -> d.t1().t1().t1()),
                    d -> d.t1().t1()
                )
        ),
        Active, List.of(
            onEvent(MerchantSuspended).to(Suspended)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("", d)).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(MerchantDeleted).to(Deleted)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("", d)).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(MerchantUpdated).toSelf()
                .assemble(c -> tuple(merge(c.log().last(DataType.json(Merchant.class)), c.input()), c.eventReference()))
                .trigger(RequestCompleted).with(d -> tuple("", d.t2())).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(MerchantLookedUp).toSelf().assemble((_, log) -> log.last(DataType.json(Merchant.class))).output(d -> d)
        ),
        Suspended, List.of(
            onEvent(MerchantResumed).to(Active)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("", d)).on(Request).identifiedBy(entityIdFromSession())
                .output(),
            onEvent(MerchantUpdated).toSelf()
                .assemble(c -> tuple(merge(c.log().last(DataType.json(Merchant.class)), c.input()), c.eventReference()))
                .trigger(RequestCompleted).with(d -> tuple("", d.t2())).on(Request).identifiedBy(entityIdFromSession())
                .output(d -> d.t1().t1()),
            onEvent(MerchantDeleted).to(Deleted)
                .assemble(TransitionContext::eventReference)
                .trigger(RequestCompleted).with(d -> tuple("", d)).on(Request).identifiedBy(entityIdFromSession())
                .output()
        ),
        Deleted, List.of(),
        Rejected, List.of()
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
