# Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, and an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions. The diagrams of the
[card payment template](cardpayments.md) are generated this way when the documentation is built.

A state diagram shows the details of an event when the mouse hovers over its name: its data, the events and actions
it triggers, and the [severity](monitoring.md#severity) of its transition if it has one.
