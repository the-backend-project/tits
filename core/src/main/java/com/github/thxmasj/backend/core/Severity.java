package com.github.thxmasj.backend.core;

/// How much attention an event calls for, for monitoring. The severity of an event is the one declared on the
/// transition accepting it, with [TransitionModelBuilder.WithToState#severity(Severity)], and is reported to the
/// [Listener] along with the event. It is not stored with the event.
public enum Severity {

  /// The normal course of things, which nobody needs to be told about. This is the severity of a transition which
  /// declares none.
  Info,

  /// Something out of the ordinary which the application handles, but which should be looked into if it keeps
  /// happening, e.g. a request to another system whose outcome is unknown
  Warning,

  /// Something has failed and is left for someone to sort out, e.g. a request which is given up
  Error,

  /// Something has failed which can't wait to be sorted out
  Critical

}
