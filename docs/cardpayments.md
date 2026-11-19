<object data="../images/com.github.thxmasj.backend.templates.cardpayment.Payment.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.templates.cardpayment.Payment.svg)

The state diagram of a settlement, which holds a merchant's credits and debits under a batch number:

<object data="../images/com.github.thxmasj.backend.templates.cardpayment.Settlement.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.templates.cardpayment.Settlement.svg)

The entity models of card payments and how they relate through triggers:

<object data="../images/com.github.thxmasj.backend.templates.cardpayment.er.svg" type="image/svg+xml"></object>

[Open the diagram in full size](images/com.github.thxmasj.backend.templates.cardpayment.er.svg)

The template is created as a `CardPayments`, which creates the entity models of the merchants, the payments and the
settlements together, as they trigger events on each other. The outbox requests of a payment or a settlement send
their outcomes back to it, so each dispatcher is given as a factory of the model it is dispatched from, which the
outbox request is built with as its process model, e.g. `payment -> AtMostOnceDispatcher.of(authentication(payment),
...)`. Its `entityModels()` are those to give the state machine, with the ones of an [inbox](http-inbox.md).
