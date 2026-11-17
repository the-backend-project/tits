# Diagrams

The diagram module draws the state diagram of an entity model with PlantUML, and an ER diagram of a state machine's
complete model, where the entity models are related through the triggers of their transitions. The ER diagram leaves
out [id](data-model.md#idevent) and [index](data-model.md#indexevent) entities, with the triggers of events on them,
as they take much space and tell little about how the entities relate.

The diagrams in this documentation, of the [card payment template](cardpayments.md), the [apps](apps.md) and the
[HTTP inboxes](http-inbox.md) and [outboxes](http-outbox.md), are generated when the documentation is built, by the
`doc-diagrams` module. It is the only module depending on the diagram module, so PlantUML is never a dependency of the
libraries or the apps.

A code example in this documentation which creates an entity model has the diagram of that model under it. The
example is written out in `DocExamples` in `doc-diagrams`, as the documentation shows it, so the diagram is drawn from
the model the example creates and the example is known to compile. A change to such an example is made in both places.
The examples of the [getting started](getting-started.md) guide are taken from the hello world demo, whose diagrams are
drawn from the demo itself.

A diagram of something other than an entity model, such as how a [rollback](http-inbox.md#rollback) relates the event
logs of several entities, is drawn by hand in the DOT language of Graphviz, in the resources of `doc-diagrams`, and
rendered by `GraphvizFormatter` with `dot`.

A state diagram shows the details of an event when the mouse hovers over its name: its data and the events and actions
it triggers. An event whose transition has a [severity](monitoring.md#severity) above `Info` is named in bold, after a
symbol and in a colour for its severity: ⚠ in amber for `Warning`, ✖ in orange for `Error` and ‼ in red for `Critical`.

The diagrams follow the colour scheme of the page. In dark mode, their arrows and the text on them are drawn in light
colours.
