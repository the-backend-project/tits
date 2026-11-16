# Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, and an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions.

The diagrams in this documentation, of the [card payment template](cardpayments.md), the [apps](apps.md) and the
[HTTP inboxes](http-inbox.md) and [outboxes](http-outbox.md), are generated when the documentation is built, by the
`doc-diagrams` module. It is the only module depending on the diagram module, so PlantUML is never a dependency of the
libraries or the apps.

A state diagram shows the details of an event when the mouse hovers over its name: its data and the events and actions
it triggers. An event whose transition has a [severity](monitoring.md#severity) above `Info` is named in bold, in a colour
for its severity: amber for `Warning`, orange for `Error` and red for `Critical`.

The diagrams follow the colour scheme of the page. In dark mode, their arrows and the text on them are drawn in light
colours.
